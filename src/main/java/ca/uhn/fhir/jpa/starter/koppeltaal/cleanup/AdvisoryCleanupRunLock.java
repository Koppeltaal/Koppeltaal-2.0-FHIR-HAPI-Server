package ca.uhn.fhir.jpa.starter.koppeltaal.cleanup;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A PostgreSQL session-level advisory lock on a connection of its own, held for the whole run; a server that dies
 * mid-run loses its session and with it the lock. It uses the two-key form, which PostgreSQL keeps apart from the
 * single-key form of the per-patient {@link AdvisoryDeleteFlowAggregateLock}.
 * <p>
 * A pool keeps the session of a returned connection open, so whenever it is unclear whether the session still holds
 * the lock, the session is ended rather than handed back.
 * </p>
 * <p>
 * Should only the lock's session be lost mid-run, the run carries on without it. A second run would then need
 * another server to reach the lock after that loss, late through clock skew or a stall, which the shared nightly
 * schedule makes unlikely: every server tries at the same moment, and one that finds the lock taken skips the night.
 * </p>
 */
public class AdvisoryCleanupRunLock implements CleanupRunLock {

	private static final Logger LOG = LoggerFactory.getLogger(AdvisoryCleanupRunLock.class);

	static final int NAMESPACE = "kt2-cleanup".hashCode();
	static final int NIGHTLY_RUN = 1;

	private final DataSource dataSource;

	public AdvisoryCleanupRunLock(DataSource dataSource) {
		this.dataSource = dataSource;
	}

	@Override
	public boolean runExclusively(Runnable cleanup) {

		final Connection connection = connect();
		try {
			if (!lock(connection)) {
				return false;
			}
			try {
				cleanup.run();
			} finally {
				release(connection);
			}
			return true;
		} finally {
			close(connection);
		}
	}

	private Connection connect() {
		try {
			return dataSource.getConnection();
		} catch (SQLException e) {
			throw new IllegalStateException("Could not take the cleanup run lock", e);
		}
	}

	/** The attempt may fail after PostgreSQL granted the lock, when only the answer got lost. */
	private static boolean lock(Connection connection) {
		try {
			// Outside a transaction, so no idle-in-transaction timeout can end the session mid-run.
			connection.setAutoCommit(true);
			return lockCall(connection, "SELECT pg_try_advisory_lock(?, ?)");
		} catch (SQLException e) {
			endSession(connection);
			throw new IllegalStateException("Could not take the cleanup run lock", e);
		}
	}

	private static void release(Connection connection) {
		try {
			lockCall(connection, "SELECT pg_advisory_unlock(?, ?)");
		} catch (SQLException e) {
			LOG.warn("Could not release the cleanup run lock, ending its session instead", e);
			endSession(connection);
		}
	}

	private static void endSession(Connection connection) {
		try {
			connection.abort(Runnable::run);
		} catch (SQLException e) {
			LOG.error("Could not end the session that may hold the cleanup run lock", e);
		}
	}

	/** After an ended session the pool cannot reset the connection; it discards it and says so here. */
	private static void close(Connection connection) {
		try {
			connection.close();
		} catch (SQLException e) {
			LOG.debug("The pool discarded the connection of the cleanup run lock", e);
		}
	}

	private static boolean lockCall(Connection connection, String query) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement(query)) {
			statement.setInt(1, NAMESPACE);
			statement.setInt(2, NIGHTLY_RUN);
			try (ResultSet result = statement.executeQuery()) {
				return result.next() && result.getBoolean(1);
			}
		}
	}
}
