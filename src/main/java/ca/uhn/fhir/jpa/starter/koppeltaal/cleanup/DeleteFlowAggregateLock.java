package ca.uhn.fhir.jpa.starter.koppeltaal.cleanup;

/**
 * Serialises the aggregate evaluation of one patient across concurrent status
 * writes. Without it two applications releasing their handbrake at the same
 * moment each still see the other holding, and no unhold is recorded at all.
 */
public interface DeleteFlowAggregateLock {

	/** Held until the surrounding transaction ends. */
	void lockPatient(String patientIdPart);

	DeleteFlowAggregateLock NONE = patientIdPart -> {
	};
}
