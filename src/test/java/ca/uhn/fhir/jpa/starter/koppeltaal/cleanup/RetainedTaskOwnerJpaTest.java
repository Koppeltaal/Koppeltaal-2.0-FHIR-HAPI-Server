package ca.uhn.fhir.jpa.starter.koppeltaal.cleanup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.jpa.test.BaseJpaR4Test;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import ca.uhn.fhir.rest.param.TokenParam;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Task;
import org.junit.jupiter.api.Test;

/** Exercises metadata writes and physical erasure against HAPI's in-memory H2 JPA store. */
class RetainedTaskOwnerJpaTest extends BaseJpaR4Test {
	private static final Instant LAST_ACTIVITY = Instant.parse("2024-09-25T10:00:00Z");

	/** The server configuration keeps $expunge and multiple deletes off; the erase is an internal step (TOP-KT-028). */
	@Test
	void erasesWithExpungeAndMultipleDeletesDisabled() {
		Fixture fixture = fixture(false);
		boolean expungeEnabled = myStorageSettings.isExpungeEnabled();
		boolean multipleDeleteAllowed = myStorageSettings.isAllowMultipleDelete();
		myStorageSettings.setExpungeEnabled(false);
		myStorageSettings.setAllowMultipleDelete(false);
		try {
			transact(() -> eraser().erase(List.of(fixture.owner()), List.of(fixture.retainedOwner()), List.of()));
		} finally {
			myStorageSettings.setExpungeEnabled(expungeEnabled);
			myStorageSettings.setAllowMultipleDelete(multipleDeleteAllowed);
		}

		assertThatThrownBy(() -> myPatientDao.read(fixture.owner(), new SystemRequestDetails()))
				.isInstanceOf(ResourceNotFoundException.class);
	}

	@Test
	void erasesTheOwnerAndTagsTheRetainedTaskWithoutRenewingItsActivity() {
		Fixture fixture = fixture(false);
		Task before = read(fixture.taskId());

		transact(() -> {
			tag(fixture);
			tag(fixture); // Repeated additions must not duplicate or replace existing metadata.
			eraser().erase(List.of(fixture.owner()), List.of(fixture.retainedOwner()), List.of());
		});

		assertThatThrownBy(() -> myPatientDao.read(fixture.owner(), new SystemRequestDetails()))
				.isInstanceOf(ResourceNotFoundException.class);
		assertThat(myPatientDao.read(fixture.beneficiary(), new SystemRequestDetails())).isNotNull();
		Task after = read(fixture.taskId());
		assertThat(after.getOwner().getReference()).isEqualTo(fixture.owner().getValue());
		assertThat(after.getMeta().getLastUpdated().toInstant()).isEqualTo(LAST_ACTIVITY);
		assertThat(after.getIdElement().getVersionIdPart()).isEqualTo(before.getIdElement().getVersionIdPart());
		assertThat(after.getMeta().getTag()).filteredOn(t -> "http://hl7.org/fhir/issue-type".equals(t.getSystem())
				&& "deleted".equals(t.getCode())).hasSize(1);
		assertThat(after.getMeta().getTag()).anySatisfy(tag -> {
			assertThat(tag.getSystem()).isEqualTo("urn:test");
			assertThat(tag.getCode()).isEqualTo("existing");
		});
		Task withoutAddedTag = after.copy();
		withoutAddedTag.getMeta().getTag().removeIf(t -> "http://hl7.org/fhir/issue-type".equals(t.getSystem()));
		assertThat(withoutAddedTag.equalsDeep(before)).isTrue();
		assertThat(read(before.getIdElement()).getOwner().getReference()).isEqualTo(fixture.owner().getValue());
		assertThat(myTaskDao.history(fixture.taskId(), null, null, null, new SystemRequestDetails()).size()).isEqualTo(1);
		var matches = myTaskDao.search(SearchParameterMap.newSynchronous()
				.add("_tag", new TokenParam("http://hl7.org/fhir/issue-type", "deleted")), new SystemRequestDetails());
		assertThat(matches.getResources(0, 10)).extracting(r -> r.getIdElement().toUnqualifiedVersionless().getValue())
				.containsExactly(fixture.taskId().getValue());
	}

	@Test
	void anUnhandledReferenceOnTheSameTaskStillBlocksAndRollsBackItsTagAndOwnerLink() {
		Fixture fixture = fixture(true);
		Task before = read(fixture.taskId());

		assertThatThrownBy(() -> transact(() -> {
			tag(fixture);
			eraser().erase(List.of(fixture.owner()), List.of(fixture.retainedOwner()), List.of());
		})).isInstanceOf(InvalidRequestException.class).hasMessageContaining("Task.focus");

		assertThat(myPatientDao.read(fixture.owner(), new SystemRequestDetails())).isNotNull();
		assertThat(read(fixture.taskId()).equalsDeep(before)).isTrue();
		assertThat(ownerLinkCount(fixture)).isEqualTo(1);
	}

	@Test
	void aBlockedReferenceOnTheSameTaskIsKeptWhileItsOwnerIsErased() {
		Fixture fixture = fixture(true);
		Task before = read(fixture.taskId());
		long beneficiaryLinks = linkCount(fixture, "Task.for");

		transact(() -> {
			tag(fixture);
			eraser().erase(List.of(fixture.owner()), List.of(fixture.retainedOwner()), List.of(fixture.taskId()));
		});

		assertThatThrownBy(() -> myPatientDao.read(fixture.owner(), new SystemRequestDetails()))
				.isInstanceOf(ResourceNotFoundException.class);
		Task after = read(fixture.taskId());
		assertThat(after.getFocus().getReference()).isEqualTo(fixture.owner().getValue());
		assertThat(after.getIdElement().getVersionIdPart()).isEqualTo(before.getIdElement().getVersionIdPart());
		assertThat(linkCount(fixture, "Task.focus")).isZero();
		assertThat(ownerLinkCount(fixture)).isZero();
		assertThat(linkCount(fixture, "Task.for")).isEqualTo(beneficiaryLinks);
	}

	@Test
	void aFailureAfterErasureRollsBackThePatientTagAndIndexTogether() {
		Fixture fixture = fixture(false);
		Task before = read(fixture.taskId());

		assertThatThrownBy(() -> transact(() -> {
			tag(fixture);
			eraser().erase(List.of(fixture.owner()), List.of(fixture.retainedOwner()), List.of());
			throw new IllegalStateException("Synthetic audit failure");
		})).isInstanceOf(IllegalStateException.class).hasMessageContaining("Synthetic audit failure");

		assertThat(myPatientDao.read(fixture.owner(), new SystemRequestDetails())).isNotNull();
		assertThat(read(fixture.taskId()).equalsDeep(before)).isTrue();
		assertThat(ownerLinkCount(fixture)).isEqualTo(1);
	}

	@Test
	void anOwnerLinkOutsideThePlanStillBlocksErasure() {
		Fixture fixture = fixture(false);
		assertThatThrownBy(() -> transact(() -> eraser().erase(List.of(fixture.owner()))))
				.isInstanceOf(InvalidRequestException.class).hasMessageContaining("Task.owner");
		assertThat(myPatientDao.read(fixture.owner(), new SystemRequestDetails())).isNotNull();
		assertThat(ownerLinkCount(fixture)).isEqualTo(1);
	}

	@Test
	void anotherTasksOwnerLinkIsNotRemovedByThePlannedException() {
		Fixture fixture = fixture(false);
		Task other = new Task().setStatus(Task.TaskStatus.READY).setIntent(Task.TaskIntent.ORDER)
				.setFor(new Reference(fixture.beneficiary())).setOwner(new Reference(fixture.owner()));
		IIdType otherId = myTaskDao.create(other, new SystemRequestDetails()).getId().toUnqualifiedVersionless();
		Task before = read(fixture.taskId());

		assertThatThrownBy(() -> transact(() -> {
			tag(fixture);
			eraser().erase(List.of(fixture.owner()), List.of(fixture.retainedOwner()), List.of());
		})).isInstanceOf(InvalidRequestException.class).hasMessageContaining("Task.owner");

		assertThat(myPatientDao.read(fixture.owner(), new SystemRequestDetails())).isNotNull();
		assertThat(read(fixture.taskId()).equalsDeep(before)).isTrue();
		assertThat(read(otherId).getOwner().getReference()).isEqualTo(fixture.owner().getValue());
		assertThat(ownerLinkCount(fixture)).isEqualTo(1);
		assertThat(ownerLinkCount(new Fixture(fixture.owner(), fixture.beneficiary(), otherId))).isEqualTo(1);
	}

	private void tag(Fixture fixture) {
		var addition = TaskOwnerTagging.prepare(read(fixture.taskId()), fixture.owner().getValue());
		myTaskDao.metaAddOperation(addition.taskId(), addition.meta(), new SystemRequestDetails());
	}

	private void transact(Runnable action) {
		new org.springframework.transaction.support.TransactionTemplate(myTxManager).executeWithoutResult(status -> action.run());
	}

	private ResourceEraser eraser() {
		return new ResourceEraser(myDeleteExpungeSvc, myIdHelperService, myEntityManager, myMemoryCacheService);
	}

	private Task read(IIdType id) { return myTaskDao.read(id, new SystemRequestDetails()); }

	private long ownerLinkCount(Fixture fixture) { return linkCount(fixture, "Task.owner"); }

	private long linkCount(Fixture fixture, String path) {
		return runInTransaction(() -> ((Number) myEntityManager.createNativeQuery("""
				SELECT count(*) FROM hfj_res_link l JOIN hfj_resource r ON r.res_id=l.src_resource_id
				WHERE r.fhir_id=:id AND r.res_type='Task' AND l.src_path=:path
				""").setParameter("id", fixture.taskId().getIdPart()).setParameter("path", path)
				.getSingleResult()).longValue());
	}

	private Fixture fixture(boolean extraReference) {
		var owner = myPatientDao.create(new Patient(), new SystemRequestDetails()).getId().toUnqualifiedVersionless();
		var beneficiary = myPatientDao.create(new Patient(), new SystemRequestDetails()).getId().toUnqualifiedVersionless();
		Task task = new Task().setStatus(Task.TaskStatus.INPROGRESS).setIntent(Task.TaskIntent.ORDER)
				.setFor(new Reference(beneficiary)).setOwner(new Reference(owner));
		task.getMeta().addTag().setSystem("urn:test").setCode("existing");
		if (extraReference) task.setFocus(new Reference(owner));
		IIdType taskId = myTaskDao.create(task, new SystemRequestDetails()).getId().toUnqualifiedVersionless();
		transact(() -> {
			myEntityManager.createNativeQuery("UPDATE hfj_resource SET res_updated=:time WHERE res_type='Task' AND fhir_id=:id")
					.setParameter("time", Timestamp.from(LAST_ACTIVITY)).setParameter("id", taskId.getIdPart()).executeUpdate();
			myEntityManager.createNativeQuery("""
					UPDATE hfj_res_ver SET res_updated=:time WHERE res_id IN
					(SELECT res_id FROM hfj_resource WHERE res_type='Task' AND fhir_id=:id)
					""").setParameter("time", Timestamp.from(LAST_ACTIVITY)).setParameter("id", taskId.getIdPart()).executeUpdate();
			myEntityManager.clear();
		});
		return new Fixture(owner, beneficiary, taskId);
	}

	private record Fixture(IIdType owner, IIdType beneficiary, IIdType taskId) {
		PatientDeletionScope.RetainedTaskOwner retainedOwner() {
			return new PatientDeletionScope.RetainedTaskOwner(taskId, owner.getValue());
		}
	}
}
