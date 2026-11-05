package ca.uhn.fhir.jpa.starter.koppeltaal.cleanup;

import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Runs the cleanup every night (TOP-KT-028): first the running cycles are judged - erased, reset or aborted - and
 * then new candidates are announced. The two runs are independent, so one failing does not keep the other from
 * running. {@code cleanup.cron} sets the moment in Dutch time; {@code "-"} turns the schedule off.
 */
public class NightlyCleanup {

	private static final Logger LOG = LoggerFactory.getLogger(NightlyCleanup.class);

	private final ErasureCleanupService erasure;
	private final CleanupService announcement;
	private final CleanupRunLock runLock;

	public NightlyCleanup(ErasureCleanupService erasure, CleanupService announcement, CleanupRunLock runLock) {
		this.erasure = erasure;
		this.announcement = announcement;
		this.runLock = runLock;
	}

	@Scheduled(cron = "${cleanup.cron:0 0 2 * * *}", zone = "Europe/Amsterdam")
	public void run() {

		final boolean ran = runLock.runExclusively(() -> {
			logged("erasure", () -> erasure.run(false, progress -> {}));
			logged("announcement", () -> announcement.run(false, progress -> {}));
		});

		if (!ran) {
			LOG.info("Another server is running the nightly cleanup, so this one skips it");
		}
	}

	private static void logged(String name, Supplier<CleanupRunReport> cleanupRun) {
		try {
			final CleanupRunReport report = cleanupRun.get();
			LOG.info("Nightly {} run {}: {} of {} patients evaluated, {} eligible, {} Tasks created, outcomes {}, "
							+ "{} errors, details {}", name, report.getStatus(), report.getPatientsEvaluated(),
					report.getPatientsTotal(), report.getPatientsEligible(), report.getTasksCreated(),
					report.getCounts(), report.getErrors().size(), report.getDetailsFile());
		} catch (RuntimeException e) {
			LOG.error("Nightly {} run failed", name, e);
		}
	}
}
