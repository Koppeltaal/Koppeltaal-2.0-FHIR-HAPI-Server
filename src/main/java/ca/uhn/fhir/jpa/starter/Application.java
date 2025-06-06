package ca.uhn.fhir.jpa.starter;

import ca.uhn.fhir.batch2.jobs.config.Batch2JobsConfig;
import ca.uhn.fhir.jpa.batch2.JpaBatch2Config;
import ca.uhn.fhir.jpa.starter.annotations.OnEitherVersion;
import ca.uhn.fhir.jpa.starter.koppeltaal.KoppeltaalRestfulServer;
import ca.uhn.fhir.jpa.starter.koppeltaal.config.FhirServerSecurityConfiguration;
import ca.uhn.fhir.jpa.starter.common.FhirTesterConfig;
import ca.uhn.fhir.jpa.starter.koppeltaal.controller.SmartConfigurationController;
import ca.uhn.fhir.jpa.starter.cdshooks.StarterCdsHooksConfig;
import ca.uhn.fhir.jpa.starter.cr.StarterCrDstu3Config;
import ca.uhn.fhir.jpa.starter.cr.StarterCrR4Config;
import ca.uhn.fhir.jpa.starter.mdm.MdmConfig;
import ca.uhn.fhir.jpa.subscription.channel.config.SubscriptionChannelConfig;
import ca.uhn.fhir.jpa.subscription.match.config.SubscriptionProcessorConfig;
import ca.uhn.fhir.jpa.subscription.match.config.WebsocketDispatcherConfig;
import ca.uhn.fhir.jpa.subscription.submit.config.SubscriptionSubmitterConfig;
import ca.uhn.fhir.rest.server.RestfulServer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.elasticsearch.ElasticsearchRestClientAutoConfiguration;
import org.springframework.boot.autoconfigure.thymeleaf.ThymeleafAutoConfiguration;
import org.springframework.boot.web.servlet.ServletComponentScan;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.boot.web.servlet.support.SpringBootServletInitializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.DispatcherServlet;

@EnableScheduling
@ServletComponentScan(basePackageClasses = {RestfulServer.class, KoppeltaalRestfulServer.class})
@SpringBootApplication(exclude = {ElasticsearchRestClientAutoConfiguration.class, ThymeleafAutoConfiguration.class})
@Import({
  SubscriptionSubmitterConfig.class,
  SubscriptionProcessorConfig.class,
  SubscriptionChannelConfig.class,
  WebsocketDispatcherConfig.class,
  MdmConfig.class,
  JpaBatch2Config.class,
  Batch2JobsConfig.class,
  FhirServerSecurityConfiguration.class,
	StarterCrR4Config.class,
	StarterCrDstu3Config.class,
	StarterCdsHooksConfig.class,
	SubscriptionSubmitterConfig.class,
	SubscriptionProcessorConfig.class,
	SubscriptionChannelConfig.class,
	WebsocketDispatcherConfig.class,
	MdmConfig.class,
	JpaBatch2Config.class,
	Batch2JobsConfig.class
})
public class Application extends SpringBootServletInitializer {

	public static void main(String[] args) {

		SpringApplication.run(Application.class, args);

		// Server is now accessible at eg. http://localhost:8080/fhir/metadata
		// UI is now accessible at http://localhost:8080/
	}

	@Autowired
	AutowireCapableBeanFactory beanFactory;

	@Bean
	@Conditional(OnEitherVersion.class)
	public ServletRegistrationBean hapiServletRegistration(RestfulServer restfulServer) {
		ServletRegistrationBean servletRegistrationBean = new ServletRegistrationBean();
		beanFactory.autowireBean(restfulServer);
		servletRegistrationBean.setServlet(restfulServer);
		servletRegistrationBean.addUrlMappings("/fhir/DEFAULT/*"); //Partitioning got changed massively in newer versions - disable it and map the servlet to our default partition
		servletRegistrationBean.setLoadOnStartup(1);

    return servletRegistrationBean;
  }

  @Bean
  public ServletRegistrationBean smartConfigurationBean() {

    AnnotationConfigWebApplicationContext annotationConfigWebApplicationContext = new AnnotationConfigWebApplicationContext();
    DispatcherServlet dispatcherServlet = new DispatcherServlet(annotationConfigWebApplicationContext);
    dispatcherServlet.setContextClass(AnnotationConfigWebApplicationContext.class);
    dispatcherServlet.setContextConfigLocation(SmartConfigurationController.class.getName());

    ServletRegistrationBean registrationBean = new ServletRegistrationBean();
    registrationBean.setName("wellknown");
    registrationBean.setServlet(dispatcherServlet);

    //TODO: Read all partitions and add a mapping in the varargs below
    registrationBean.addUrlMappings("/fhir/DEFAULT/.well-known/*");
    registrationBean.setLoadOnStartup(1);
    return registrationBean;
  }
}
