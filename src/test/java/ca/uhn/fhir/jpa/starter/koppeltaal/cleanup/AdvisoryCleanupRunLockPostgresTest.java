package ca.uhn.fhir.jpa.starter.koppeltaal.cleanup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/** Every lock opens its own connection here, as two servers of a cluster would. */
@EnabledIfSystemProperty(named = "cleanup.postgres.tests", matches = "true")
class AdvisoryCleanupRunLockPostgresTest {

	private String url;
	private String user;
	private DataSource dataSource;

	@BeforeEach
	void setUp() {
		url = System.getProperty("cleanup.jdbc.url", "jdbc:postgresql://127.0.0.1:5432/hapi_fhir_server_prd_restore");
		user = System.getProperty("cleanup.jdbc.user", System.getProperty("user.name"));
		dataSource = new DriverManagerDataSource(url, user, "");
	}

	@Test
	void aSecondServerSkipsWhileTheFirstRuns() {
		AtomicBoolean secondRan = new AtomicBoolean(true);

		boolean firstRan = new AdvisoryCleanupRunLock(dataSource).runExclusively(
				() -> secondRan.set(new AdvisoryCleanupRunLock(dataSource).runExclusively(() -> {})));

		assertThat(firstRan).isTrue();
		assertThat(secondRan).isFalse();
	}

	/** A pool keeps the session of a returned connection open, so the lock has to be released explicitly. */
	@Test
	void theLockIsFreeAgainAfterAFailedRun() {
		SingleConnectionDataSource pooled = new SingleConnectionDataSource(url, user, "", true);
		try {
			assertThatThrownBy(() -> new AdvisoryCleanupRunLock(pooled).runExclusively(() -> {
				throw new IllegalStateException("run failed");
			})).hasMessage("run failed");

			assertThat(new AdvisoryCleanupRunLock(dataSource).runExclusively(() -> {})).isTrue();
		} finally {
			pooled.destroy();
		}
	}

	/**
	 * A status write locks its patient with the single-key form. Even a single key with the very same bits as the
	 * run lock does not hold the nightly run off, because PostgreSQL keeps the two key spaces apart.
	 */
	@Test
	void aPatientLockNeverHoldsOffTheNightlyRun() {
		long sameBits = ((long) AdvisoryCleanupRunLock.NAMESPACE << 32) | AdvisoryCleanupRunLock.NIGHTLY_RUN;
		AtomicBoolean ran = new AtomicBoolean();

		new JdbcTemplate(dataSource).execute((Connection patientWrite) -> {
			patientWrite.setAutoCommit(false);
			try (Statement statement = patientWrite.createStatement()) {
				statement.execute("SELECT pg_advisory_xact_lock(" + sameBits + ")");
				ran.set(new AdvisoryCleanupRunLock(dataSource).runExclusively(() -> {}));
			} finally {
				patientWrite.rollback();
			}
			return null;
		});

		assertThat(ran).isTrue();
	}
}
