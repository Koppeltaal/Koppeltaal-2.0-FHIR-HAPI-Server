package ca.uhn.fhir.jpa.starter.koppeltaal.interceptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ca.uhn.fhir.jpa.test.BaseJpaR4Test;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import ca.uhn.fhir.rest.server.exceptions.ForbiddenOperationException;
import ca.uhn.fhir.rest.server.exceptions.UnprocessableEntityException;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Task;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The guard has to sit on the storage pointcuts the DAO really fires, for creates and deletes alike. */
class DeleteFlowMarkerJpaTest extends BaseJpaR4Test {

	private final DeleteFlowMarkerInterceptor guard = new DeleteFlowMarkerInterceptor();

	@BeforeEach
	void registerGuard() {
		myInterceptorRegistry.registerInterceptor(guard);
	}

	@AfterEach
	void unregisterGuard() {
		myInterceptorRegistry.unregisterInterceptor(guard);
	}

	@Test
	void anApplicationCannotDeleteALabelledTaskButTheServerCan() {
		IIdType task = myTaskDao.create(labelled(new Task().setStatus(Task.TaskStatus.REQUESTED)
				.setIntent(Task.TaskIntent.ORDER)), new SystemRequestDetails()).getId().toUnqualifiedVersionless();

		assertThatThrownBy(() -> myTaskDao.delete(task, mySrd)).isInstanceOf(ForbiddenOperationException.class);
		assertThat(myTaskDao.read(task, new SystemRequestDetails())).isNotNull();
		assertThatCode(() -> myTaskDao.delete(task, new SystemRequestDetails())).doesNotThrowAnyException();
	}

	@Test
	void anApplicationCannotCreateALabelledPatient() {
		assertThatThrownBy(() -> myPatientDao.create(labelled(new Patient()), mySrd))
				.isInstanceOf(UnprocessableEntityException.class);
	}

	private static <T extends org.hl7.fhir.r4.model.DomainResource> T labelled(T resource) {
		resource.getMeta().addSecurity("http://vzvz.nl/fhir/CodeSystem/koppeltaal-security-label", "kt2-delete-flow", null);
		return resource;
	}
}
