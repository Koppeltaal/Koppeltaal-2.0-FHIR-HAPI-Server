package nl.koppeltaal.hapi.autoconfigure;

import nl.koppeltaal.hapi.controller.SmartConfigurationController;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.DispatcherServlet;

@Configuration(proxyBeanMethods = false)
public class ServletWiringConfig {

  @Bean
  public static FhirServletMapping fhirServletMapping() {
    return new FhirServletMapping();
  }

  @Bean
  public FilterRegistrationBean<RequestIdFilter> requestIdFilter() {
    FilterRegistrationBean<RequestIdFilter> registration = new FilterRegistrationBean<>(new RequestIdFilter());
    registration.addUrlPatterns(FhirServletMapping.FHIR_URL_MAPPING);
    return registration;
  }

  /** The SMART configuration under the FHIR base path, moved unchanged from the fork's Application. */
  @Bean
  public ServletRegistrationBean<DispatcherServlet> smartConfigurationBean() {
    AnnotationConfigWebApplicationContext context = new AnnotationConfigWebApplicationContext();
    DispatcherServlet dispatcherServlet = new DispatcherServlet(context);
    dispatcherServlet.setContextClass(AnnotationConfigWebApplicationContext.class);
    dispatcherServlet.setContextConfigLocation(SmartConfigurationController.class.getName());

    ServletRegistrationBean<DispatcherServlet> registration = new ServletRegistrationBean<>(dispatcherServlet);
    registration.setName("wellknown");
    registration.addUrlMappings("/fhir/DEFAULT/.well-known/*");
    registration.setLoadOnStartup(1);
    return registration;
  }
}
