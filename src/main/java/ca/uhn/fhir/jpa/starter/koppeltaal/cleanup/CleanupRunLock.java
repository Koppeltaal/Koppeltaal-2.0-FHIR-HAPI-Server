package ca.uhn.fhir.jpa.starter.koppeltaal.cleanup;

/**
 * Keeps the nightly cleanup to one server of a cluster at a time. Two servers announcing the same patient at once
 * would each find no Task yet and both create one.
 */
public interface CleanupRunLock {

	/** Runs the cleanup unless another server holds the lock, and tells whether it ran. */
	boolean runExclusively(Runnable cleanup);
}
