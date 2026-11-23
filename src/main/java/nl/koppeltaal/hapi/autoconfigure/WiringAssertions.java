package nl.koppeltaal.hapi.autoconfigure;

import ca.uhn.fhir.interceptor.api.IInterceptorService;
import ca.uhn.fhir.jpa.api.config.JpaStorageSettings;
import ca.uhn.fhir.jpa.interceptor.CascadingDeleteInterceptor;
import ca.uhn.fhir.jpa.interceptor.validation.RepositoryValidatingInterceptor;
import ca.uhn.fhir.rest.server.RestfulServer;
import ca.uhn.fhir.rest.server.interceptor.CorsInterceptor;
import ca.uhn.fhir.rest.server.interceptor.RequestValidatingInterceptor;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletRegistration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.springframework.boot.web.servlet.ServletRegistrationBean;

/**
 * Stops the startup when the starter wires a feature Koppeltaal replaces, the FHIR servlet is not remapped, or the
 * storage settings lack the UUID resource ids and full-text indexing that KoppeltaalStorageSettings forces.
 */
final class WiringAssertions {

  private WiringAssertions() {
  }

  static void verify(RestfulServer server, IInterceptorService jpaRegistry, ServletContext servletContext,
      List<ServletRegistrationBean<?>> remapped, Collection<Object> koppeltaalInterceptors,
      JpaStorageSettings storageSettings) {
    List<String> problems = new ArrayList<>();
    // A JpaStorageSettings created before the post-processor registered would keep the configured values.
    if (storageSettings.getResourceServerIdStrategy() != JpaStorageSettings.IdStrategyEnum.UUID) {
      problems.add("resource ids must be UUIDs, found " + storageSettings.getResourceServerIdStrategy()
        + ": KoppeltaalStorageSettings did not reach JpaStorageSettings");
    }
    if (!storageSettings.isHibernateSearchIndexFullText()) {
      problems.add("full-text indexing must be on: KoppeltaalStorageSettings did not reach JpaStorageSettings");
    }
    if (remapped.size() != 1) {
      problems.add("expected one FHIR servlet registration to remap, found " + remapped.size());
    } else {
      String servletName = remapped.get(0).getServletName();
      ServletRegistration registration = servletContext.getServletRegistration(servletName);
      if (registration == null) {
        problems.add("the servlet context has no registration " + servletName + " for the FHIR servlet: this check "
          + "reads the effective mapping, so it needs the embedded server (a mock servlet context has no registrations)");
      } else if (!List.copyOf(registration.getMappings()).equals(List.of(FhirServletMapping.FHIR_URL_MAPPING))) {
        problems.add("expected Tomcat to map the FHIR servlet to exactly " + FhirServletMapping.FHIR_URL_MAPPING
          + ", found " + registration.getMappings());
      }
    }
    List<Object> serverInterceptors = server.getInterceptorService().getAllRegisteredInterceptors();
    // Storage hooks run on both registries (HAPI combines them), so the features that act through them count on both.
    List<Object> storageInterceptors = new ArrayList<>(serverInterceptors);
    storageInterceptors.addAll(jpaRegistry.getAllRegisteredInterceptors());
    if (storageInterceptors.stream().anyMatch(interceptor -> interceptor.getClass() == CascadingDeleteInterceptor.class)) {
      problems.add("hapi.fhir.allow_cascading_deletes must not be set: Koppeltaal registers its own "
        + "resource-origin-aware cascading deletes (koppeltaal.cascading-deletes)");
    }
    if (hasForeign(serverInterceptors, CorsInterceptor.class, koppeltaalInterceptors)) {
      problems.add("hapi.fhir.cors must not be set: Koppeltaal registers its own CorsInterceptor (koppeltaal.cors)");
    }
    if (hasForeign(storageInterceptors, RepositoryValidatingInterceptor.class, koppeltaalInterceptors)) {
      problems.add("hapi.fhir.enable_repository_validating_interceptor must not be set: Koppeltaal registers its own "
        + "rules (koppeltaal.repository-validation)");
    }
    if (serverInterceptors.stream().filter(RequestValidatingInterceptor.class::isInstance).count() > 1) {
      problems.add("more than one RequestValidatingInterceptor is registered");
    }
    if (!problems.isEmpty()) {
      throw new IllegalStateException("The Koppeltaal FHIR server is miswired: " + String.join("; ", problems));
    }
  }

  private static boolean hasForeign(List<Object> registered, Class<?> type, Collection<Object> koppeltaalInterceptors) {
    return registered.stream().filter(type::isInstance)
      .anyMatch(interceptor -> koppeltaalInterceptors.stream().noneMatch(own -> own == interceptor));
  }
}
