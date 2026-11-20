package nl.koppeltaal.hapi.autoconfigure;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.interceptor.api.IInterceptorBroadcaster;
import ca.uhn.fhir.interceptor.api.IInterceptorService;
import ca.uhn.fhir.interceptor.executor.InterceptorService;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.delete.ThreadSafeResourceDeleterSvc;
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
import nl.koppeltaal.hapi.interceptor.ResourceOriginCascadingDeleteInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.servlet.ServletRegistrationBean;

class WiringAssertionsTest {

  private static final String SERVLET_NAME = "hapiServlet";

  private final FhirContext fhirContext = FhirContext.forR4Cached();
  private final RestfulServer server = new RestfulServer(fhirContext);
  private final IInterceptorService jpaRegistry = new InterceptorService();
  private final ServletContext servletContext = mock(ServletContext.class);
  private final List<ServletRegistrationBean<?>> remapped = new ArrayList<>();
  private final Collection<Object> koppeltaalInterceptors = new ArrayList<>();

  WiringAssertionsTest() {
    ServletRegistrationBean<RestfulServer> registration =
      new ServletRegistrationBean<>(server, FhirServletMapping.FHIR_URL_MAPPING);
    registration.setName(SERVLET_NAME);
    remapped.add(registration);
    mapTheFhirServletTo(FhirServletMapping.FHIR_URL_MAPPING);
  }

  @Test
  void acceptsTheKoppeltaalWiring() {
    CorsInterceptor cors = new CorsInterceptor();
    RepositoryValidatingInterceptor rules = new RepositoryValidatingInterceptor(fhirContext, List.of());
    koppeltaalInterceptors.addAll(List.of(cors, rules));
    server.registerInterceptor(cors);
    server.registerInterceptor(rules);
    server.registerInterceptor(new RequestValidatingInterceptor());
    server.registerInterceptor(new ResourceOriginCascadingDeleteInterceptor(fhirContext, mock(DaoRegistry.class),
      mock(IInterceptorBroadcaster.class), mock(ThreadSafeResourceDeleterSvc.class), true));

    assertThatCode(this::verify).doesNotThrowAnyException();
  }

  @Test
  void refusesThePlainCascadeOnTheServer() {
    server.registerInterceptor(plainCascade());

    assertThatThrownBy(this::verify).hasMessageContaining("hapi.fhir.allow_cascading_deletes must be false");
  }

  /** Storage hooks run on both registries (CompositeInterceptorBroadcaster), so a plain cascade there counts too. */
  @Test
  void refusesThePlainCascadeOnTheJpaRegistry() {
    jpaRegistry.registerInterceptor(plainCascade());

    assertThatThrownBy(this::verify).hasMessageContaining("hapi.fhir.allow_cascading_deletes must be false");
  }

  @Test
  void refusesAForeignCorsInterceptor() {
    server.registerInterceptor(new CorsInterceptor());

    assertThatThrownBy(this::verify).hasMessageContaining("hapi.fhir.cors must not be set");
  }

  @Test
  void refusesAForeignRepositoryValidatorOnTheServer() {
    server.registerInterceptor(new RepositoryValidatingInterceptor(fhirContext, List.of()));

    assertThatThrownBy(this::verify)
      .hasMessageContaining("hapi.fhir.enable_repository_validating_interceptor must be false");
  }

  @Test
  void refusesAForeignRepositoryValidatorOnTheJpaRegistry() {
    jpaRegistry.registerInterceptor(new RepositoryValidatingInterceptor(fhirContext, List.of()));

    assertThatThrownBy(this::verify)
      .hasMessageContaining("hapi.fhir.enable_repository_validating_interceptor must be false");
  }

  @Test
  void refusesASecondRequestValidator() {
    server.registerInterceptor(new RequestValidatingInterceptor());
    server.registerInterceptor(new RequestValidatingInterceptor());

    assertThatThrownBy(this::verify).hasMessageContaining("more than one RequestValidatingInterceptor");
  }

  @Test
  void refusesAnotherEffectiveMapping() {
    mapTheFhirServletTo("/fhir/*");

    assertThatThrownBy(this::verify)
      .hasMessageContaining("expected Tomcat to map the FHIR servlet to exactly /fhir/DEFAULT/*, found [/fhir/*]");
  }

  /** A mock servlet context knows no registrations; the check needs the embedded server and says so. */
  @Test
  void explainsAMissingServletRegistration() {
    when(servletContext.getServletRegistration(SERVLET_NAME)).thenReturn(null);

    assertThatThrownBy(this::verify)
      .hasMessageContaining("the servlet context has no registration hapiServlet")
      .hasMessageContaining("embedded server");
  }

  @Test
  void refusesWhenNoFhirServletWasRemapped() {
    remapped.clear();

    assertThatThrownBy(this::verify).hasMessageContaining("expected one FHIR servlet registration to remap, found 0");
  }

  private void verify() {
    WiringAssertions.verify(server, jpaRegistry, servletContext, remapped, koppeltaalInterceptors);
  }

  private void mapTheFhirServletTo(String mapping) {
    ServletRegistration registration = mock(ServletRegistration.class);
    when(registration.getMappings()).thenReturn(List.of(mapping));
    when(servletContext.getServletRegistration(SERVLET_NAME)).thenReturn(registration);
  }

  private CascadingDeleteInterceptor plainCascade() {
    return new CascadingDeleteInterceptor(fhirContext, mock(DaoRegistry.class), mock(IInterceptorBroadcaster.class),
      mock(ThreadSafeResourceDeleterSvc.class));
  }
}
