package ca.uhn.fhir.jpa.starter;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.starter.koppeltaal.config.FhirServerSecurityConfiguration;
import ca.uhn.fhir.jpa.starter.koppeltaal.config.SmartBackendServiceConfiguration;
import ca.uhn.fhir.jpa.starter.koppeltaal.interceptor.InjectResourceOriginInterceptor;
import ca.uhn.fhir.jpa.starter.koppeltaal.interceptor.JwtSecurityInterceptor;
import ca.uhn.fhir.jpa.starter.koppeltaal.interceptor.Oauth2UrisStatementInterceptorForR4;
import ca.uhn.fhir.jpa.starter.koppeltaal.interceptor.ResourceOriginAuthorizationInterceptor;
import ca.uhn.fhir.jpa.starter.koppeltaal.interceptor.ResourceOriginSearchNarrowingInterceptor;
import ca.uhn.fhir.jpa.starter.koppeltaal.service.SmartBackendServiceAuthorizationService;
import ca.uhn.fhir.parser.StrictErrorHandler;
import ca.uhn.fhir.rest.server.interceptor.CaptureResourceSourceFromHeaderInterceptor;
import javax.servlet.ServletException;
import org.hl7.fhir.r4.model.Device;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

@Import(AppProperties.class)
public class JpaRestfulServer extends BaseJpaRestfulServer {

	@Autowired
	AppProperties appProperties;

	@Autowired
	FhirServerSecurityConfiguration fhirServerSecurityConfiguration;

	@Autowired
	private SmartBackendServiceAuthorizationService smartBackendServiceAuthorizationService;

	@Autowired
	private SmartBackendServiceConfiguration smartBackendServiceConfiguration;

	private static final long serialVersionUID = 1L;

	public JpaRestfulServer() {
		super();
	}

	@Override
	protected void initialize() throws ServletException {
		super.initialize();

//		final FhirContext fhirContext = getFhirContext();
//		fhirContext.setParserErrorHandler(new StrictErrorHandler());
//		setFhirContext(fhirContext);

		// Add your own customization here
		if (fhirServerSecurityConfiguration.isEnabled()) {
			registerInterceptor(new JwtSecurityInterceptor(oauth2AccessTokenService));

			IFhirResourceDao<Device> deviceDao = daoRegistry.getResourceDao(Device.class);
			registerInterceptor(new InjectResourceOriginInterceptor(daoRegistry, deviceDao, smartBackendServiceConfiguration)); // can only determine this from the Bearer token
			registerInterceptor(new ResourceOriginAuthorizationInterceptor(daoRegistry, deviceDao, smartBackendServiceAuthorizationService, smartBackendServiceConfiguration));
		   registerInterceptor(new ResourceOriginSearchNarrowingInterceptor(daoRegistry, deviceDao, smartBackendServiceAuthorizationService));
		}

		registerInterceptor(new Oauth2UrisStatementInterceptorForR4(fhirServerSecurityConfiguration));

		//  Allow users to set the meta.source with the "X-Request-Source" header
		registerInterceptor(new CaptureResourceSourceFromHeaderInterceptor(getFhirContext()));

		// Register our custom structured definitions
		interceptorService.registerInterceptor(factory.build());
	}
}
