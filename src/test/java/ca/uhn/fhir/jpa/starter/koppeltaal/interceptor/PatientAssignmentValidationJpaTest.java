package ca.uhn.fhir.jpa.starter.koppeltaal.interceptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ca.uhn.fhir.jpa.partition.IRequestPartitionHelperSvc;
import ca.uhn.fhir.jpa.test.BaseJpaR4Test;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import ca.uhn.fhir.rest.server.exceptions.UnprocessableEntityException;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r4.model.CareTeam;
import org.hl7.fhir.r4.model.DomainResource;
import org.hl7.fhir.r4.model.Meta;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.RelatedPerson;
import org.hl7.fhir.r4.model.Task;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The erase run tags a Task it keeps for manual review through HAPI's own $meta-add, which re-fires the update hooks
 * on the stored Task. The legacy Tasks below predate the validation, so they are stored before it is registered.
 */
class PatientAssignmentValidationJpaTest extends BaseJpaR4Test {

	@Autowired
	private IRequestPartitionHelperSvc partitions;
	private PatientAssignmentValidationInterceptor validation;

	@BeforeEach
	void createValidation() {
		// H2 has no advisory locks; the validation only needs the call to succeed.
		runInTransaction(() -> myEntityManager
				.createNativeQuery("CREATE ALIAS IF NOT EXISTS pg_advisory_xact_lock FOR 'java.lang.Integer.compare'")
				.executeUpdate());
		validation = new PatientAssignmentValidationInterceptor(myDaoRegistry, partitions, myStorageSettings,
				myEntityManager);
	}

	@AfterEach
	void unregisterValidation() {
		myInterceptorRegistry.unregisterInterceptor(validation);
	}

	@Test
	void aLegacyTaskWithAConflictingOwnerTakesTheReviewTag() {
		IIdType task = legacyTaskOwnedByAnotherPatientsRelative();
		myInterceptorRegistry.registerInterceptor(validation);

		assertThatCode(() -> tag(task)).doesNotThrowAnyException();

		Task tagged = myTaskDao.read(task, new SystemRequestDetails());
		assertThat(tagged.getMeta().getTag()).anyMatch(tag -> "http://hl7.org/fhir/issue-type".equals(tag.getSystem())
				&& "deleted".equals(tag.getCode()));
		assertThat(tagged.getIdElement().getVersionIdPart()).isEqualTo("1");
	}

	@Test
	void aTaskWhoseOwnerIsGoneTakesTheReviewTagAgain() {
		IIdType patient = create(new Patient());
		IIdType team = create(new CareTeam().setSubject(new Reference(patient)));
		IIdType task = create(task(patient, team));
		myInterceptorRegistry.registerInterceptor(validation);
		tag(task);

		// An earlier erase took the owner along with its own patient.
		myStorageSettings.setEnforceReferentialIntegrityOnDelete(false);
		myDaoRegistry.getResourceDao(CareTeam.class).delete(team, new SystemRequestDetails());

		assertThatCode(() -> tag(task)).doesNotThrowAnyException();
	}

	@Test
	void aContentChangeToALegacyTaskWithAConflictingOwnerIsStillRefused() {
		IIdType task = legacyTaskOwnedByAnotherPatientsRelative();
		myInterceptorRegistry.registerInterceptor(validation);
		Task cancelled = myTaskDao.read(task, new SystemRequestDetails()).setStatus(Task.TaskStatus.CANCELLED);

		assertThatThrownBy(() -> myTaskDao.update(cancelled, new SystemRequestDetails()))
				.isInstanceOf(UnprocessableEntityException.class).hasMessageContaining("RelatedPerson.patient");
	}

	private IIdType legacyTaskOwnedByAnotherPatientsRelative() {
		IIdType relativesPatient = create(new Patient());
		IIdType beneficiary = create(new Patient());
		IIdType relative = create(new RelatedPerson().setPatient(new Reference(relativesPatient)));
		return create(task(beneficiary, relative));
	}

	private void tag(IIdType task) {
		Meta review = new Meta();
		review.addTag("http://hl7.org/fhir/issue-type", "deleted", "Deleted");
		myTaskDao.metaAddOperation(task, review, new SystemRequestDetails());
	}

	private static Task task(IIdType beneficiary, IIdType owner) {
		return new Task().setStatus(Task.TaskStatus.REQUESTED).setIntent(Task.TaskIntent.ORDER)
				.setFor(new Reference(beneficiary)).setOwner(new Reference(owner));
	}

	private IIdType create(DomainResource resource) {
		return myDaoRegistry.getResourceDao(resource.fhirType()).create(resource, new SystemRequestDetails())
				.getId().toUnqualifiedVersionless();
	}
}
