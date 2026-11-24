package nl.koppeltaal.hapi.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.server.RestfulServer;
import jakarta.servlet.http.HttpServlet;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.servlet.ServletRegistrationBean;

class FhirServletMappingTest {

  private final FhirServletMapping mapping = new FhirServletMapping();

  @Test
  void mapsTheFhirServletToTheKoppeltaalBasePath() {
    ServletRegistrationBean<RestfulServer> registration =
      new ServletRegistrationBean<>(new RestfulServer(FhirContext.forR4Cached()), "/fhir/*");

    mapping.postProcessBeforeInitialization(registration, "hapiServletRegistration");

    assertThat(registration.getUrlMappings()).containsExactly("/fhir/DEFAULT/*");
    assertThat(mapping.remappedRegistrations()).containsExactly(registration);
  }

  @Test
  void leavesOtherServletsAlone() {
    ServletRegistrationBean<HttpServlet> registration = new ServletRegistrationBean<>(new HttpServlet() {}, "/other/*");

    mapping.postProcessBeforeInitialization(registration, "other");

    assertThat(registration.getUrlMappings()).containsExactly("/other/*");
    assertThat(mapping.remappedRegistrations()).isEmpty();
  }
}
