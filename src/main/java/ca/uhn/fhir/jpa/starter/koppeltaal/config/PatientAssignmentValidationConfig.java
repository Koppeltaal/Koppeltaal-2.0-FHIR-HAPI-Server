package ca.uhn.fhir.jpa.starter.koppeltaal.config;

import ca.uhn.fhir.interceptor.api.IInterceptorService;
import ca.uhn.fhir.jpa.api.config.JpaStorageSettings;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.partition.IRequestPartitionHelperSvc;
import ca.uhn.fhir.jpa.starter.koppeltaal.interceptor.PatientAssignmentValidationInterceptor;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.orm.jpa.SharedEntityManagerCreator;

@Configuration(proxyBeanMethods = false)
public class PatientAssignmentValidationConfig {

	@Bean
	public PatientAssignmentValidationInterceptor patientAssignmentValidationInterceptor(DaoRegistry registry,
			IRequestPartitionHelperSvc partitions, JpaStorageSettings settings, EntityManagerFactory entityManagerFactory,
			IInterceptorService interceptors) {
		PatientAssignmentValidationInterceptor interceptor = new PatientAssignmentValidationInterceptor(registry,
				partitions, settings, SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory));
		interceptors.registerInterceptor(interceptor);
		return interceptor;
	}
}
