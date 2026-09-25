package ca.uhn.fhir.jpa.starter.koppeltaal.cleanup;

import ca.uhn.fhir.interceptor.model.RequestPartitionId;
import ca.uhn.fhir.jpa.api.svc.IDeleteExpungeSvc;
import ca.uhn.fhir.jpa.api.svc.IIdHelperService;
import ca.uhn.fhir.jpa.api.svc.ResolveIdentityMode;
import ca.uhn.fhir.jpa.model.dao.JpaPid;
import ca.uhn.fhir.jpa.util.MemoryCacheService;

import jakarta.persistence.EntityManager;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.hl7.fhir.instance.model.api.IIdType;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * <p>
 * The hard erase of OPG-014: every version wiped, a later read a 404 rather
 * than a 410, and nothing left to reach through {@code _history}.
 * </p>
 * <p>
 * An ordinary DAO delete cannot do that - it writes a tombstone and keeps the
 * history, so the data stays readable. Neither can instance-level
 * {@code $expunge}, which clears the search index by source and then trips over
 * the retained AuditEvents pointing back at the patient. This class drives
 * HAPI's batch delete-expunge,
 * which clears {@code HFJ_RES_LINK} by target as well, so those AuditEvents
 * keep their stored reference without holding the patient row hostage.
 * </p>
 */
public class ResourceEraser {

	/**
	 * Older versions keep their payload in a PostgreSQL large object and hold
	 * only its OID in this column. The batch deletes the row that names the OID
	 * without unlinking the object, and nothing else does either, so those bytes
	 * would outlive the 404.
	 */
	private static final String LEGACY_PAYLOADS =
			"SELECT count(*) FROM hfj_res_ver WHERE res_id IN (:ids) AND res_text IS NOT NULL";

	private final IDeleteExpungeSvc<JpaPid> deleteExpungeSvc;
	private final IIdHelperService<JpaPid> idHelper;
	private final EntityManager entityManager;
	private final MemoryCacheService memoryCacheService;

	public ResourceEraser(IDeleteExpungeSvc<JpaPid> deleteExpungeSvc, IIdHelperService<JpaPid> idHelper,
			EntityManager entityManager, MemoryCacheService memoryCacheService) {

		this.deleteExpungeSvc = deleteExpungeSvc;
		this.idHelper = idHelper;
		this.entityManager = entityManager;
		this.memoryCacheService = memoryCacheService;
	}

	/**
	 * Wipes all of it in one statement batch. Caller supplies a transaction: the
	 * erase and the proof that it happened have to commit together.
	 *
	 * @return the number of resources removed
	 */
	public int erase(List<IIdType> resources) {
		return erase(resources, List.of());
	}

	public int erase(List<IIdType> resources, List<PatientDeletionScope.RetainedTaskOwner> retainedTaskOwners) {
		if (!TransactionSynchronizationManager.isActualTransactionActive()) {
			throw new IllegalStateException("Erasure requires an active transaction");
		}
		entityManager.flush();

		final List<JpaPid> pids = idHelper.resolveResourcePids(RequestPartitionId.allPartitions(), resources,
				ResolveIdentityMode.includeDeleted().noCacheUnlessDeletesDisabled());

		if (pids.size() != resources.size()) {
			throw new IllegalStateException(String.format(
					"Refusing to erase: %d of the %d listed resources no longer resolve, so the run no longer "
							+ "knows what it is removing", resources.size() - pids.size(), resources.size()));
		}

		requireNoLegacyPayloads(pids);
		removeRetainedOwnerLinks(resources, retainedTaskOwners);

		// No cascade: other inbound references still stop the erase. Only retained
		// AuditEvents and the explicitly planned Task.owner links may remain in JSON.
		final int erased = deleteExpungeSvc.deleteExpunge(pids, false, null);
		entityManager.clear();
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCompletion(int status) {
				if (status == STATUS_COMMITTED) {
					// HAPI queues ID-cache puts even for uncached lookups. Evict after
					// those afterCommit callbacks, or a warm vread can dereference a missing row.
					memoryCacheService.invalidateCaches(
							MemoryCacheService.CacheEnum.RESOURCE_LOOKUP_BY_FORCED_ID,
							MemoryCacheService.CacheEnum.PID_TO_FORCED_ID,
							MemoryCacheService.CacheEnum.HISTORY_COUNT,
							MemoryCacheService.CacheEnum.MATCH_URL,
							MemoryCacheService.CacheEnum.RESOURCE_CONDITIONAL_CREATE_VERSION);
				}
			}
		});
		return erased;
	}

	private void removeRetainedOwnerLinks(List<IIdType> resources,
			List<PatientDeletionScope.RetainedTaskOwner> retainedTaskOwners) {
		Set<String> erased = resources.stream().map(id -> id.toUnqualifiedVersionless().getValue())
				.collect(Collectors.toSet());
		for (var owner : retainedTaskOwners) {
			IIdType taskId = owner.taskId();
			IIdType patientId = new org.hl7.fhir.r4.model.IdType(owner.patientReference());
			if (!"Task".equals(taskId.getResourceType()) || taskId.hasBaseUrl()
					|| !"Patient".equals(patientId.getResourceType()) || patientId.hasBaseUrl()
					|| erased.contains(taskId.toUnqualifiedVersionless().getValue())
					|| !erased.contains(patientId.toUnqualifiedVersionless().getValue())) {
				throw new IllegalStateException("Retained Task owner does not match the deletion scope");
			}
			// The target is about to disappear. Remove only its planned owner index;
			// keep the Task JSON and timestamps intact. The surrounding transaction
			// restores this index too if tagging, erasure or its audit fails.
			entityManager.createNativeQuery("""
					DELETE FROM hfj_res_link WHERE src_path='Task.owner'
					  AND src_resource_id IN (SELECT res_id FROM hfj_resource WHERE res_type='Task' AND fhir_id=:taskId)
					  AND target_resource_id IN (SELECT res_id FROM hfj_resource WHERE res_type='Patient' AND fhir_id=:patientId)
					""")
					.setParameter("taskId", taskId.getIdPart())
					.setParameter("patientId", patientId.getIdPart())
					.executeUpdate();
		}
	}

	private void requireNoLegacyPayloads(List<JpaPid> pids) {

		final Number stranded = (Number) entityManager.createNativeQuery(LEGACY_PAYLOADS)
				.setParameter("ids", JpaPid.toLongList(pids))
				.getSingleResult();

		if (stranded.longValue() > 0) {
			throw new IllegalStateException(String.format(
					"Refusing to erase: %d of the versions in scope store their payload as a large object, which "
							+ "this erase would leave behind unreadable through the API but present on disk",
					stranded.longValue()));
		}
	}
}
