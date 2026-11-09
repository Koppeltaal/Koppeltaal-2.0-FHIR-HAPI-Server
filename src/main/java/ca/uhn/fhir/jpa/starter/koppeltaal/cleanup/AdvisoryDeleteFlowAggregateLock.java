package ca.uhn.fhir.jpa.starter.koppeltaal.cleanup;

import jakarta.persistence.EntityManager;

/**
 * A PostgreSQL transaction-scoped advisory lock. It needs no table of its own
 * and is released when the transaction of the status write ends, which is
 * exactly the window in which the aggregate is evaluated.
 */
public class AdvisoryDeleteFlowAggregateLock implements DeleteFlowAggregateLock {

	private static final String LOCK_NAMESPACE = "kt2-delete-flow:";

	private final EntityManager entityManager;

	public AdvisoryDeleteFlowAggregateLock(EntityManager entityManager) {
		this.entityManager = entityManager;
	}

	@Override
	public void lockPatient(String patientIdPart) {
		entityManager.createNativeQuery("SELECT pg_advisory_xact_lock(?1)")
				.setParameter(1, lockKey(patientIdPart))
				.getSingleResult();
	}

	/**
	 * A collision only makes two patients wait for each other, which is harmless
	 * at the volume of these writes.
	 */
	private static long lockKey(String patientIdPart) {
		return (LOCK_NAMESPACE + patientIdPart).hashCode();
	}
}
