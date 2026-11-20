package nl.koppeltaal.hapi.autoconfigure;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.interceptor.api.IInterceptorBroadcaster;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.delete.ThreadSafeResourceDeleterSvc;
import ca.uhn.fhir.jpa.interceptor.validation.RepositoryValidatingInterceptor;
import ca.uhn.fhir.jpa.interceptor.validation.RepositoryValidatingRuleBuilder;
import ca.uhn.fhir.rest.server.interceptor.CorsInterceptor;
import java.util.Arrays;
import nl.koppeltaal.hapi.config.FhirServerSecurityConfiguration;
import nl.koppeltaal.hapi.interceptor.ResourceOriginCascadingDeleteInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.cors.CorsConfiguration;

/**
 * Koppeltaal's replacements for features the starter builds itself. Each has its own koppeltaal.* switch; the starter's
 * switch for the same feature stays off, and WiringAssertions refuses both.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(KoppeltaalCorsProperties.class)
public class StarterReplacementConfig {

  private static final Logger ourLog = LoggerFactory.getLogger(StarterReplacementConfig.class);

  /** Authorizes every cascaded delete against the requester's resource-origin permissions. */
  @Bean
  @ConditionalOnProperty(name = "koppeltaal.cascading-deletes.enabled", havingValue = "true")
  public ResourceOriginCascadingDeleteInterceptor koppeltaalCascadingDeleteInterceptor(FhirContext fhirContext,
      DaoRegistry daoRegistry, IInterceptorBroadcaster interceptorBroadcaster,
      ThreadSafeResourceDeleterSvc resourceDeleter, FhirServerSecurityConfiguration security) {
    return new ResourceOriginCascadingDeleteInterceptor(fhirContext, daoRegistry, interceptorBroadcaster,
      resourceDeleter, security.isEnabled());
  }

  /** The starter's CORS configuration, plus If-Match, which updates need. */
  @Bean
  @ConditionalOnProperty(name = "koppeltaal.cors.enabled", havingValue = "true")
  public CorsInterceptor koppeltaalCorsInterceptor(KoppeltaalCorsProperties cors) {
    ourLog.info("CORS is enabled on this server");
    CorsConfiguration config = new CorsConfiguration();
    config.addAllowedHeader(HttpHeaders.ORIGIN);
    config.addAllowedHeader(HttpHeaders.ACCEPT);
    config.addAllowedHeader(HttpHeaders.CONTENT_TYPE);
    config.addAllowedHeader(HttpHeaders.AUTHORIZATION);
    config.addAllowedHeader(HttpHeaders.CACHE_CONTROL);
    config.addAllowedHeader(HttpHeaders.IF_MATCH);
    config.addAllowedHeader("x-fhir-starter");
    config.addAllowedHeader("X-Requested-With");
    config.addAllowedHeader("Prefer");
    cors.getAllowedOrigin().forEach(config::addAllowedOriginPattern);
    ourLog.info("CORS allows the following origins: " + String.join(", ", cors.getAllowedOrigin()));
    config.addExposedHeader("Location");
    config.addExposedHeader("Content-Location");
    config.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH", "HEAD"));
    config.setAllowCredentials(cors.isAllowCredentials());
    return new CorsInterceptor(config);
  }

  /**
   * The KT2 profile rules. The starter registers this bean through its Optional parameter. Spring builds it while it
   * resolves restfulServer(...)'s arguments, one before IPackageInstallerSvc, so before the IG install, as today.
   */
  @Bean
  @ConditionalOnProperty(name = "koppeltaal.repository-validation.enabled", havingValue = "true")
  public RepositoryValidatingInterceptor koppeltaalRepositoryValidatingInterceptor(
      RepositoryValidatingRuleBuilder ruleBuilder, DaoRegistry daoRegistry) {
    return new KoppeltaalRepositoryValidationRules(ruleBuilder, daoRegistry).buildUsingStoredStructureDefinitions();
  }
}
