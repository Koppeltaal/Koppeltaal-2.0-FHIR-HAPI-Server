package ca.uhn.fhir.jpa.starter.koppeltaal.cleanup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/** The failure paths a real database does not produce on demand. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdvisoryCleanupRunLockTest {

	@Mock
	private DataSource dataSource;
	@Mock
	private Connection connection;
	@Mock
	private PreparedStatement lockStatement;
	@Mock
	private ResultSet lockResult;

	@BeforeEach
	void lockIsTakenButCannotBeReleased() throws SQLException {
		when(dataSource.getConnection()).thenReturn(connection);
		when(connection.prepareStatement("SELECT pg_try_advisory_lock(?, ?)")).thenReturn(lockStatement);
		when(lockStatement.executeQuery()).thenReturn(lockResult);
		when(lockResult.next()).thenReturn(true);
		when(lockResult.getBoolean(1)).thenReturn(true);
		when(connection.prepareStatement("SELECT pg_advisory_unlock(?, ?)"))
				.thenThrow(new SQLException("canceling statement due to user request"));
		// What a pool reports when it gets back a connection whose session was ended.
		doThrow(new SQLException("This connection has been closed.")).when(connection).close();
	}

	/** A pooled connection would otherwise go back with its session, and the lock, still alive. */
	@Test
	void aLockThatCannotBeReleasedEndsItsSessionAndTheRunStillCounts() throws SQLException {
		assertThat(new AdvisoryCleanupRunLock(dataSource).runExclusively(() -> {})).isTrue();

		verify(connection).abort(any());
	}

	@Test
	void aFailedReleaseDoesNotHideWhyTheRunFailed() {
		assertThatThrownBy(() -> new AdvisoryCleanupRunLock(dataSource).runExclusively(() -> {
			throw new IllegalStateException("run failed");
		})).hasMessage("run failed");
	}

	/** PostgreSQL may have granted the lock before the answer got lost, so the session goes as well. */
	@Test
	void anUncertainLockAttemptEndsItsSessionWithoutRunning() throws SQLException {
		when(lockStatement.executeQuery()).thenThrow(new SQLException("canceling statement due to statement timeout"));
		AtomicBoolean ran = new AtomicBoolean();

		assertThatThrownBy(() -> new AdvisoryCleanupRunLock(dataSource).runExclusively(() -> ran.set(true)))
				.hasMessage("Could not take the cleanup run lock");

		verify(connection).abort(any());
		assertThat(ran).isFalse();
	}
}
