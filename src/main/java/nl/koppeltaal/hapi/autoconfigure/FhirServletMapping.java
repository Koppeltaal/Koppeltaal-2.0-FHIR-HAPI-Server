package nl.koppeltaal.hapi.autoconfigure;

import ca.uhn.fhir.rest.server.RestfulServer;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.web.servlet.ServletRegistrationBean;

/** Maps the starter's FHIR servlet from /fhir/* to the Koppeltaal base path /fhir/DEFAULT/*. */
public class FhirServletMapping implements BeanPostProcessor {

  public static final String FHIR_URL_MAPPING = "/fhir/DEFAULT/*";

  private final List<ServletRegistrationBean<?>> remapped = new CopyOnWriteArrayList<>();

  @Override
  public Object postProcessBeforeInitialization(Object bean, String beanName) {
    if (bean instanceof ServletRegistrationBean<?> registration && registration.getServlet() instanceof RestfulServer) {
      registration.setUrlMappings(List.of(FHIR_URL_MAPPING));
      remapped.add(registration);
    }
    return bean;
  }

  /** The registrations this post-processor remapped; WiringAssertions checks their final mappings. */
  public List<ServletRegistrationBean<?>> remappedRegistrations() {
    return List.copyOf(remapped);
  }
}
