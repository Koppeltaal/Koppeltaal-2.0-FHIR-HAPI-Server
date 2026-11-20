package nl.koppeltaal.hapi.autoconfigure;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.interceptor.api.IInterceptorBroadcaster;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.delete.ThreadSafeResourceDeleterSvc;
import nl.koppeltaal.hapi.config.FhirServerSecurityConfiguration;
import nl.koppeltaal.hapi.interceptor.ResourceOriginCascadingDeleteInterceptor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Koppeltaal's replacements for features the starter builds itself. Each has its own koppeltaal.* switch; the starter's
 * switch for the same feature stays off, and WiringAssertions refuses both.
 */
@Configuration(proxyBeanMethods = false)
public class StarterReplacementConfig {

  /** Authorizes every cascaded delete against the requester's resource-origin permissions. */
  @Bean
  @ConditionalOnProperty(name = "koppeltaal.cascading-deletes.enabled", havingValue = "true")
  public ResourceOriginCascadingDeleteInterceptor koppeltaalCascadingDeleteInterceptor(FhirContext fhirContext,
      DaoRegistry daoRegistry, IInterceptorBroadcaster interceptorBroadcaster,
      ThreadSafeResourceDeleterSvc resourceDeleter, FhirServerSecurityConfiguration security) {
    return new ResourceOriginCascadingDeleteInterceptor(fhirContext, daoRegistry, interceptorBroadcaster,
      resourceDeleter, security.isEnabled());
  }
}
