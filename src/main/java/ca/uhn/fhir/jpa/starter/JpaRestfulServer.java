package ca.uhn.fhir.jpa.starter;

import ca.uhn.fhir.jpa.api.config.DaoConfig;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.starter.koppeltaal.config.*;
import ca.uhn.fhir.jpa.starter.koppeltaal.interceptor.*;
import ca.uhn.fhir.jpa.starter.koppeltaal.service.SmartBackendServiceAuthorizationService;
import ca.uhn.fhir.rest.openapi.OpenApiInterceptor;
import ca.uhn.fhir.rest.server.interceptor.CaptureResourceSourceFromHeaderInterceptor;
import org.hl7.fhir.r4.model.Device;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import javax.servlet.ServletException;

@Import(AppProperties.class)
public class JpaRestfulServer extends BaseJpaRestfulServer {

	private static final long serialVersionUID = 1L;
	@Autowired
	AppProperties appProperties;
	@Autowired
	FhirServerSecurityConfiguration fhirServerSecurityConfiguration;
	@Autowired
	FhirServerAuditLogConfiguration fhirServerAuditLogConfiguration;
	@Autowired
	private SmartBackendServiceAuthorizationService smartBackendServiceAuthorizationService;
	@Autowired
	private SmartBackendServiceConfiguration smartBackendServiceConfiguration;
	@Autowired
	private AuditEventInterceptor auditEventInterceptor;
  @Autowired
  private InjectCorrelationIdInterceptor injectCorrelationIdInterceptor;
	@Autowired
  private InjectTraceIdInterceptor injectTraceIdInterceptor;
	@Autowired
	private AuditEventSubscriptionInterceptor auditEventSubscriptionInterceptor;

	@Autowired
	private AuditEventIntergityInterceptor auditEventIntergityInterceptor;

	@Autowired
	private OpenApiConfiguration openApiConfiguration;

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

			//The SubscriptionInterceptor is somehow not properly registered with `registerInterceptor`, but does work via the `interceptorService`
			// Figured it out, there a 2 InterceptorService(s). One in the constructor of {@RestfulServer}:
			// {code}
			// new InterceptorService("RestfulServer")
			// {code}
			// And one JPA version. They both do DIFFERENT things. The horror.
			interceptorService.registerInterceptor(new SubscriptionInterceptor(daoRegistry, deviceDao, smartBackendServiceAuthorizationService));

		}

		if (fhirServerAuditLogConfiguration.isEnabled()) {
			registerInterceptor(auditEventInterceptor);
      registerInterceptor(injectCorrelationIdInterceptor);
      registerInterceptor(injectTraceIdInterceptor);
			// Yes, this is ANOTHER interceptor.
			interceptorService.registerInterceptor(auditEventSubscriptionInterceptor);
			registerInterceptor(auditEventIntergityInterceptor);
		}


		registerInterceptor(new EnforceIfMatchHeaderInterceptor());
		registerInterceptor(new Oauth2UrisStatementInterceptorForR4(fhirServerSecurityConfiguration));

		//  Allow users to set the meta.source with the "X-Request-Source" header
		registerInterceptor(new CaptureResourceSourceFromHeaderInterceptor(getFhirContext()));

		// Register our custom structured definitions
		registerInterceptor(factory.build());

		// Register the OpenApi Interceptor
		if (openApiConfiguration.isEnabled()) {
			registerInterceptor(new OpenApiInterceptor());
		}

    daoConfig.setResourceServerIdStrategy(DaoConfig.IdStrategyEnum.UUID);
	}
}
