package ca.uhn.fhir.jpa.starter.koppeltaal;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.interceptor.api.IInterceptorService;
import ca.uhn.fhir.jpa.api.config.DaoConfig;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.interceptor.OverridePathBasedReferentialIntegrityForDeletesInterceptor;
import ca.uhn.fhir.jpa.starter.AppProperties;
import ca.uhn.fhir.jpa.starter.koppeltaal.config.FhirServerAuditLogConfiguration;
import ca.uhn.fhir.jpa.starter.koppeltaal.config.FhirServerSecurityConfiguration;
import ca.uhn.fhir.jpa.starter.koppeltaal.config.OpenApiConfiguration;
import ca.uhn.fhir.jpa.starter.koppeltaal.config.SmartBackendServiceConfiguration;
import ca.uhn.fhir.jpa.starter.koppeltaal.interceptor.*;
import ca.uhn.fhir.jpa.starter.koppeltaal.service.Oauth2AccessTokenService;
import ca.uhn.fhir.jpa.starter.koppeltaal.service.SmartBackendServiceAuthorizationService;
import ca.uhn.fhir.rest.openapi.OpenApiInterceptor;
import ca.uhn.fhir.rest.server.RestfulServer;
import ca.uhn.fhir.rest.server.interceptor.CaptureResourceSourceFromHeaderInterceptor;
import org.hl7.fhir.r4.model.Device;
import org.springframework.beans.factory.annotation.Autowired;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import java.util.UUID;

@WebServlet(urlPatterns = { "/fhir/*" }, displayName = "Koppeltaal Resource Service")
public class KoppeltaalRestfulServer extends RestfulServer {

	private static final long serialVersionUID = 1L;

  @Autowired
  AppProperties appProperties;
  @Autowired
  Oauth2AccessTokenService oauth2AccessTokenService;
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

  @Autowired
  private CapabilityStatementInterceptor capabilityStatementInterceptor;
  @Autowired
  private DaoRegistry daoRegistry;
  @Autowired
  private DaoConfig daoConfig;

  @Autowired
  private OverridePathBasedReferentialIntegrityForDeletesInterceptor referentialIntegrityDeleteInterceptor;

  @Autowired
  private MimeTypeInterceptor mimeTypeInterceptor;

  @Autowired
  private IInterceptorService myInterceptorRegistry;

	public KoppeltaalRestfulServer(FhirContext context) {
		super(context);
	}

	@Override
	protected void initialize() throws ServletException {

		// Add your own customization here
    registerInterceptor(mimeTypeInterceptor);

			IFhirResourceDao<Device> deviceDao = daoRegistry.getResourceDao(Device.class);
		if (fhirServerSecurityConfiguration.isEnabled()) {
			registerInterceptor(new JwtSecurityInterceptor(oauth2AccessTokenService));

			registerInterceptor(new InjectResourceOriginInterceptor(daoRegistry, deviceDao, smartBackendServiceConfiguration)); // can only determine this from the Bearer token
			registerInterceptor(new ResourceOriginAuthorizationInterceptor(daoRegistry, deviceDao, smartBackendServiceConfiguration));
			registerInterceptor(new ResourceOriginSearchNarrowingInterceptor(daoRegistry, deviceDao));

		}
			//The SubscriptionInterceptor is somehow not properly registered with `registerInterceptor`, but does work via the `interceptorService`
			// Figured it out, there a 2 InterceptorService(s). One in the constructor of {@RestfulServer}:
			// {code}
			// new InterceptorService("RestfulServer")
			// {code}
			// And one JPA version. They both do DIFFERENT things. The horror.
      SubscriptionNarrowingInterceptor subscriptionNarrowingInterceptor = new SubscriptionNarrowingInterceptor(daoRegistry);
      myInterceptorRegistry.registerInterceptor(subscriptionNarrowingInterceptor);
      registerInterceptor(subscriptionNarrowingInterceptor);

		if (fhirServerAuditLogConfiguration.isEnabled()) {
			registerInterceptor(auditEventInterceptor);
      registerInterceptor(injectCorrelationIdInterceptor);
      registerInterceptor(injectTraceIdInterceptor);
			// Yes, this is ANOTHER interceptor.
      myInterceptorRegistry.registerInterceptor(auditEventSubscriptionInterceptor);
			registerInterceptor(auditEventSubscriptionInterceptor);
			registerInterceptor(auditEventIntergityInterceptor);
		}

    registerInterceptor(capabilityStatementInterceptor);

		registerInterceptor(new EnforceIfMatchHeaderInterceptor());
		registerInterceptor(new Oauth2UrisStatementInterceptorForR4(fhirServerSecurityConfiguration));

		//  Allow users to set the meta.source with the "X-Request-Source" header
		registerInterceptor(new CaptureResourceSourceFromHeaderInterceptor(getFhirContext()));

		// Register our custom structured definitions
//		registerInterceptor(factory.build());

		// Register the OpenApi Interceptor
		if (openApiConfiguration.isEnabled()) {
			registerInterceptor(new OpenApiInterceptor());
		}

    // Disable referential integrity for AuditEvent.entity.what
    referentialIntegrityDeleteInterceptor.addPath("AuditEvent.entity.what");
    registerInterceptor(referentialIntegrityDeleteInterceptor);

    registerInterceptor(new DefaultDescendingSortInterceptor());

    daoConfig.setResourceServerIdStrategy(DaoConfig.IdStrategyEnum.UUID);
	}

  @Override
  protected String newRequestId(int theRequestIdLength) {
    return UUID.randomUUID().toString();
  }
}
