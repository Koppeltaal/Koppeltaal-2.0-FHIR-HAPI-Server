package nl.koppeltaal.hapi.autoconfigure;

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

/** Stops the startup when the starter wires a feature Koppeltaal replaces, or the FHIR servlet is not remapped. */
final class WiringAssertions {

  private WiringAssertions() {
  }

  static void verify(RestfulServer server, ServletContext servletContext, List<ServletRegistrationBean<?>> remapped,
      Collection<Object> koppeltaalInterceptors) {
    List<String> problems = new ArrayList<>();
    if (remapped.size() != 1) {
      problems.add("expected one FHIR servlet registration to remap, found " + remapped.size());
    } else {
      ServletRegistration registration = servletContext.getServletRegistration(remapped.get(0).getServletName());
      Collection<String> effective = registration == null ? List.of() : registration.getMappings();
      if (!List.copyOf(effective).equals(List.of(FhirServletMapping.FHIR_URL_MAPPING))) {
        problems.add("expected Tomcat to map the FHIR servlet to exactly " + FhirServletMapping.FHIR_URL_MAPPING
          + ", found " + effective);
      }
    }
    List<Object> registered = server.getInterceptorService().getAllRegisteredInterceptors();
    if (registered.stream().anyMatch(interceptor -> interceptor.getClass() == CascadingDeleteInterceptor.class)) {
      problems.add("hapi.fhir.allow_cascading_deletes must be false: Koppeltaal registers its own "
        + "resource-origin-aware cascading deletes (koppeltaal.cascading-deletes)");
    }
    if (hasForeign(registered, CorsInterceptor.class, koppeltaalInterceptors)) {
      problems.add("hapi.fhir.cors must not be set: Koppeltaal registers its own CorsInterceptor (koppeltaal.cors)");
    }
    if (hasForeign(registered, RepositoryValidatingInterceptor.class, koppeltaalInterceptors)) {
      problems.add("hapi.fhir.enable_repository_validating_interceptor must be false: Koppeltaal registers its own "
        + "rules (koppeltaal.repository-validation)");
    }
    if (registered.stream().filter(RequestValidatingInterceptor.class::isInstance).count() > 1) {
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
