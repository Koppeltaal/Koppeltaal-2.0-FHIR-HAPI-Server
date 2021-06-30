package ca.uhn.fhir.jpa.starter;

import ca.uhn.fhir.jpa.starter.koppeltaal.config.FhirServerSecurityConfiguration;
import ca.uhn.fhir.jpa.starter.koppeltaal.interceptor.JwtSecurityInterceptor;
import ca.uhn.fhir.jpa.starter.koppeltaal.interceptor.Oauth2UrisStatementInterceptorForR4;
import ca.uhn.fhir.rest.server.interceptor.CaptureResourceSourceFromHeaderInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import javax.servlet.ServletException;

@Import(AppProperties.class)
public class JpaRestfulServer extends BaseJpaRestfulServer {

  @Autowired
  AppProperties appProperties;

  @Autowired
  FhirServerSecurityConfiguration fhirServerSecurityConfiguration;

  private static final long serialVersionUID = 1L;

  public JpaRestfulServer() {
    super();
  }

  @Override
  protected void initialize() throws ServletException {
    super.initialize();

	  // Add your own customization here
	  if(fhirServerSecurityConfiguration.isEnabled()) {
		  registerInterceptor(new JwtSecurityInterceptor(oauth2AccessTokenService));
	  }

	  registerInterceptor(new Oauth2UrisStatementInterceptorForR4(fhirServerSecurityConfiguration));

	  //  Allow users to set the meta.source with the "X-Request-Source" header
	  registerInterceptor(new CaptureResourceSourceFromHeaderInterceptor(getFhirContext()));

	  // Register our custom  structured definitions
//	  interceptorService.registerInterceptor(factory.build());

  }

}
