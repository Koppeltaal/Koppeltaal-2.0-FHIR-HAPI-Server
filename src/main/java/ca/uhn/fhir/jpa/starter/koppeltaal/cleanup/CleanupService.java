package ca.uhn.fhir.jpa.starter.koppeltaal.cleanup;

/**
 * Seam for the AVG cleanup logic. The console controller (and eventually a
 * cron trigger) invokes this; the implementation performs the announcement
 * run described in the opschoning IG page.
 */
public interface CleanupService {

	/**
	 * Executes one cleanup run. With dryRun=true the full selection is evaluated
	 * and reported, but nothing is written. The progress listener receives
	 * intermediate RUNNING snapshots so a UI can show live progress.
	 */
	CleanupRunReport run(boolean dryRun, java.util.function.Consumer<CleanupRunReport> progressListener);
}
