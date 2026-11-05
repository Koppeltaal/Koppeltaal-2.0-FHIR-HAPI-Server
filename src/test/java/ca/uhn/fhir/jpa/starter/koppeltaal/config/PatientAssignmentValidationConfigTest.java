package ca.uhn.fhir.jpa.starter.koppeltaal.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.interceptor.api.HookParams;
import ca.uhn.fhir.interceptor.api.IInterceptorService;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.interceptor.executor.InterceptorService;
import ca.uhn.fhir.jpa.api.config.JpaStorageSettings;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.partition.IRequestPartitionHelperSvc;
import ca.uhn.fhir.rest.api.InterceptorInvocationTimingEnum;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import ca.uhn.fhir.rest.api.server.storage.TransactionDetails;
import ca.uhn.fhir.rest.server.exceptions.UnprocessableEntityException;
import ca.uhn.fhir.rest.server.servlet.ServletRequestDetails;
import jakarta.persistence.EntityManagerFactory;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Task;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class PatientAssignmentValidationConfigTest {
	@Test
	void validatesStorageWritesWhenCleanupAndRestAreDisabled() {
		InterceptorService registry = new InterceptorService();
		new ApplicationContextRunner()
				.withUserConfiguration(PatientAssignmentValidationConfig.class)
				.withPropertyValues("cleanup.enabled=false", "hapi.fhir.rest.server.enabled=false")
				.withBean(IInterceptorService.class, () -> registry)
				.withBean(FhirContext.class, FhirContext::forR4Cached)
				.withBean(DaoRegistry.class, () -> mock(DaoRegistry.class))
				.withBean(IRequestPartitionHelperSvc.class, () -> mock(IRequestPartitionHelperSvc.class))
				.withBean(JpaStorageSettings.class, JpaStorageSettings::new)
				.withBean(EntityManagerFactory.class, () -> mock(EntityManagerFactory.class))
				.run(context -> {
					assertThat(context).hasNotFailed();
					var owner = new org.hl7.fhir.r4.model.RelatedPerson().setPatient(new Reference("Patient/patient-a"));
					owner.setId("owner");
					Task invalid = new Task().setOwner(new Reference("#owner")).setFor(new Reference("Patient/patient-b"));
					invalid.addContained(owner);
					HookParams params = new HookParams().add(IBaseResource.class, invalid)
							.add(RequestDetails.class, new SystemRequestDetails())
							.add(ServletRequestDetails.class, null)
							.add(TransactionDetails.class, new TransactionDetails())
							.add(InterceptorInvocationTimingEnum.class, InterceptorInvocationTimingEnum.ACTIVE);
					assertThatThrownBy(() -> registry.callHooks(Pointcut.STORAGE_PRECOMMIT_RESOURCE_CREATED, params))
							.isInstanceOf(UnprocessableEntityException.class);
				});
	}
}
