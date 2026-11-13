package ca.uhn.fhir.jpa.starter.koppeltaal.deactivation;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.starter.koppeltaal.config.SmartBackendServiceConfiguration;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Stops clients that keep resending rejected requests. The KoppeltaalRestfulServer registers the interceptor only with
 * security enabled: it needs the client id from the access token.
 */
@Configuration
public class ClientDeactivationConfig {

	@Bean
	public RepeatedRejectionDetector repeatedRejectionDetector(ClientDeactivationProperties properties) {
		return new RepeatedRejectionDetector(properties.getWindow(), properties.getMinRejections(),
				properties.getMinRepeatFactor(), properties.getMaxFingerprints(), Clock.systemUTC());
	}

	@Bean(destroyMethod = "close")
	public DomainAdminDeactivationClient domainAdminDeactivationClient(ClientDeactivationProperties properties) {
		return new DomainAdminDeactivationClient(properties.getDomainAdminUrl(), properties.getToken(),
				properties.getRetryInterval(), properties.getMaxAttempts());
	}

	@Bean
	public ClientDeactivationInterceptor clientDeactivationInterceptor(ClientDeactivationProperties properties,
			RepeatedRejectionDetector detector, DomainAdminDeactivationClient domainAdmin, DaoRegistry daoRegistry,
			SmartBackendServiceConfiguration smartBackendServiceConfiguration) {
		return new ClientDeactivationInterceptor(properties, detector, domainAdmin, daoRegistry,
				smartBackendServiceConfiguration.getDomainAdminClientId(), Clock.systemUTC());
	}
}
