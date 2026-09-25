package ca.uhn.fhir.jpa.starter.koppeltaal.cleanup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ca.uhn.fhir.jpa.api.svc.IDeleteExpungeSvc;
import ca.uhn.fhir.jpa.api.svc.IIdHelperService;
import ca.uhn.fhir.jpa.model.dao.JpaPid;
import ca.uhn.fhir.jpa.util.MemoryCacheService;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

import java.util.List;

import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r4.model.IdType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class ResourceEraserTest {

	private final IDeleteExpungeSvc<JpaPid> deleteExpungeSvc = mock(IDeleteExpungeSvc.class);
	private final IIdHelperService<JpaPid> idHelper = mock(IIdHelperService.class);
	private final EntityManager entityManager = mock(EntityManager.class);
	private final Query legacyPayloadCount = mock(Query.class);
	private final MemoryCacheService memoryCacheService = new MemoryCacheService(new ca.uhn.fhir.jpa.api.config.JpaStorageSettings());
	private final ResourceEraser eraser = new ResourceEraser(deleteExpungeSvc, idHelper, entityManager, memoryCacheService);

	@BeforeEach
	void noLegacyPayloadsByDefault() {
		TransactionSynchronizationManager.initSynchronization();
		TransactionSynchronizationManager.setActualTransactionActive(true);
		when(entityManager.createNativeQuery(any(String.class))).thenReturn(legacyPayloadCount);
		when(legacyPayloadCount.setParameter(any(String.class), any())).thenReturn(legacyPayloadCount);
		when(legacyPayloadCount.getSingleResult()).thenReturn(0L);
	}

	@AfterEach
	void clearTransaction() {
		TransactionSynchronizationManager.clear();
	}

	@Test
	void committingTheEraseEvictsLookupsRepopulatedByHapisAfterCommit() {
		resolveTo(JpaPid.fromId(1L), JpaPid.fromId(2L), JpaPid.fromId(3L));
		var key = new MemoryCacheService.ForcedIdCacheKey("Patient", "patient-1",
				ca.uhn.fhir.interceptor.model.RequestPartitionId.allPartitions());
		memoryCacheService.putAfterCommit(MemoryCacheService.CacheEnum.RESOURCE_LOOKUP_BY_FORCED_ID, key, "stale PID");

		eraser.erase(SCOPE);
		var synchronizations = TransactionSynchronizationManager.getSynchronizations();
		synchronizations.forEach(TransactionSynchronization::afterCommit);
		synchronizations.forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_COMMITTED));

		assertThat((Object) memoryCacheService.getIfPresent(MemoryCacheService.CacheEnum.RESOURCE_LOOKUP_BY_FORCED_ID, key)).isNull();
		verify(entityManager).clear();
	}

	@Test
	void rollingBackLeavesTheStillValidLookupCached() {
		resolveTo(JpaPid.fromId(1L), JpaPid.fromId(2L), JpaPid.fromId(3L));
		var key = new MemoryCacheService.ForcedIdCacheKey("Patient", "patient-1",
				ca.uhn.fhir.interceptor.model.RequestPartitionId.allPartitions());
		memoryCacheService.put(MemoryCacheService.CacheEnum.RESOURCE_LOOKUP_BY_FORCED_ID, key, "existing PID");

		eraser.erase(SCOPE);
		TransactionSynchronizationManager.getSynchronizations().forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

		assertThat((Object) memoryCacheService.getIfPresent(MemoryCacheService.CacheEnum.RESOURCE_LOOKUP_BY_FORCED_ID, key)).isEqualTo("existing PID");
	}

	private static final List<IIdType> SCOPE = List.of(
			new IdType("Task", "task-1"),
			new IdType("CareTeam", "careteam-1"),
			new IdType("Patient", "patient-1"));

	@Test
	void everythingInScopeGoesInOneBatch() {

		resolveTo(JpaPid.fromId(1L), JpaPid.fromId(2L), JpaPid.fromId(3L));
		when(deleteExpungeSvc.deleteExpunge(anyList(), eq(false), eq(null))).thenReturn(3);

		assertThat(eraser.erase(SCOPE)).isEqualTo(3);

		final ArgumentCaptor<List<JpaPid>> erased = ArgumentCaptor.forClass(List.class);
		verify(deleteExpungeSvc).deleteExpunge(erased.capture(), eq(false), eq(null));
		assertThat(erased.getValue()).containsExactly(JpaPid.fromId(1L), JpaPid.fromId(2L), JpaPid.fromId(3L));
	}

	/**
	 * Cascading would pull in whatever refers to the scope, and a Task owned by
	 * this patient can be about another one. The erase has to stop instead.
	 */
	@Test
	void theBatchNeverCascades() {

		resolveTo(JpaPid.fromId(1L), JpaPid.fromId(2L), JpaPid.fromId(3L));

		eraser.erase(SCOPE);

		verify(deleteExpungeSvc).deleteExpunge(anyList(), eq(false), eq(null));
	}

	/**
	 * A resource that vanished between listing and erasing means the run no
	 * longer matches its own dry run - and if it was the patient, erasing the
	 * rest would leave the record it was all about.
	 */
	@Test
	void aResourceThatNoLongerResolvesStopsTheErase() {

		resolveTo(JpaPid.fromId(1L), JpaPid.fromId(2L));

		assertThatThrownBy(() -> eraser.erase(SCOPE))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("1 of the 3 listed resources no longer resolve");

		verify(deleteExpungeSvc, never()).deleteExpunge(anyList(), any(Boolean.class), any());
	}

	/**
	 * The batch removes the row that names the large object without unlinking
	 * the object, so a 404 would be handed out over payload bytes still on disk.
	 */
	@Test
	void aLegacyLargeObjectPayloadStopsTheErase() {

		resolveTo(JpaPid.fromId(1L), JpaPid.fromId(2L), JpaPid.fromId(3L));
		when(legacyPayloadCount.getSingleResult()).thenReturn(7L);

		assertThatThrownBy(() -> eraser.erase(SCOPE))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("7 of the versions in scope store their payload as a large object");

		verify(deleteExpungeSvc, never()).deleteExpunge(anyList(), any(Boolean.class), any());
	}

	@Test
	void refusesToDetachAnOwnerOutsideTheEraseScope() {
		resolveTo(JpaPid.fromId(1L), JpaPid.fromId(2L), JpaPid.fromId(3L));
		var retained = new PatientDeletionScope.RetainedTaskOwner(new IdType("Task/retained"), "Patient/unrelated");

		assertThatThrownBy(() -> eraser.erase(SCOPE, List.of(retained)))
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("deletion scope");

		verify(legacyPayloadCount, never()).executeUpdate();
		verify(deleteExpungeSvc, never()).deleteExpunge(anyList(), eq(false), eq(null));
	}

	@Test
	void refusesToTreatAnErasedTaskAsRetained() {
		resolveTo(JpaPid.fromId(1L), JpaPid.fromId(2L), JpaPid.fromId(3L));
		var retained = new PatientDeletionScope.RetainedTaskOwner(new IdType("Task/task-1"), "Patient/patient-1");

		assertThatThrownBy(() -> eraser.erase(SCOPE, List.of(retained)))
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("deletion scope");

		verify(legacyPayloadCount, never()).executeUpdate();
		verify(deleteExpungeSvc, never()).deleteExpunge(anyList(), eq(false), eq(null));
	}

	private void resolveTo(JpaPid... pids) {
		when(idHelper.resolveResourcePids(any(), anyList(), any())).thenReturn(List.of(pids));
	}
}
