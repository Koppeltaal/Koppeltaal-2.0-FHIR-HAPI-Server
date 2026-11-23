package nl.koppeltaal.hapi.autoconfigure;

import ca.uhn.fhir.batch2.config.Batch2JobRegisterer;
import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.interceptor.api.IInterceptorBroadcaster;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.delete.ThreadSafeResourceDeleterSvc;
import ca.uhn.fhir.jpa.interceptor.validation.RepositoryValidatingInterceptor;
import ca.uhn.fhir.jpa.interceptor.validation.RepositoryValidatingRuleBuilder;
import ca.uhn.fhir.jpa.packages.IPackageInstallerSvc;
import ca.uhn.fhir.jpa.packages.PackageInstallationSpec;
import ca.uhn.fhir.rest.server.interceptor.CorsInterceptor;
import java.util.Arrays;
import java.util.Map;
import nl.koppeltaal.hapi.config.FhirServerSecurityConfiguration;
import nl.koppeltaal.hapi.interceptor.ResourceOriginCascadingDeleteInterceptor;
import nl.koppeltaal.hapi.service.PackageInstallerWithExclusionSvcImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
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

  /** UUID resource ids and full-text indexing, fixed as the fork's code fixed them. */
  @Bean
  public static KoppeltaalStorageSettings koppeltaalStorageSettings() {
    return new KoppeltaalStorageSettings();
  }

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

  /**
   * The Koppeltaal packageInstaller. It starts Batch2, then installs every entry of koppeltaal.implementationguides in
   * configuration order, each without example instances, as the fork did with hapi.fhir.implementationguides. The entries
   * take the same keys (HAPI's PackageInstallationSpec). It is the primary IPackageInstallerSvc and has its own name, so
   * the starter's packageInstaller, which is also primary, cannot replace it silently: the two together fail the startup.
   */
  @Bean("koppeltaalPackageInstaller")
  @Primary
  public IPackageInstallerSvc koppeltaalPackageInstaller(PackageInstallerWithExclusionSvcImpl installer,
      Batch2JobRegisterer batch2JobRegisterer, Environment environment) {
    batch2JobRegisterer.start();
    Binder binder = Binder.get(environment);
    Map<String, PackageInstallationSpec> guides = binder
      .bind("koppeltaal.implementationguides", Bindable.mapOf(String.class, PackageInstallationSpec.class))
      .orElse(Map.of());
    boolean installTransitiveIgDependencies = binder
      .bind("hapi.fhir.install-transitive-ig-dependencies", Boolean.class)
      .orElse(true);
    guides.forEach((key, guide) -> {
      if (isBlank(guide.getName()) || isBlank(guide.getVersion())) {
        throw new IllegalStateException("koppeltaal.implementationguides." + key + " needs a name and a version, as "
          + "HAPI's package installer does; found name " + guide.getName() + " and version " + guide.getVersion());
      }
      if (installTransitiveIgDependencies) {
        guide.addDependencyExclude("hl7.fhir.r2.core")
          .addDependencyExclude("hl7.fhir.r3.core")
          .addDependencyExclude("hl7.fhir.r4.core")
          .addDependencyExclude("hl7.fhir.r5.core");
      }
      // Binary example instances exceed varchar(4000); install configuration and definitions only
      guide.addInstallResourceTypes("StructureDefinition", "ValueSet", "CodeSystem", "SearchParameter",
        "CapabilityStatement", "OperationDefinition", "ConceptMap", "NamingSystem");
      // so the installed version is queryable at runtime
      guide.addInstallResourceTypes("ImplementationGuide");
      installer.install(guide);
    });
    return installer;
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
