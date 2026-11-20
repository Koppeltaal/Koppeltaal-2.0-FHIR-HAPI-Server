package nl.koppeltaal.hapi.autoconfigure;

import ca.uhn.fhir.interceptor.api.IInterceptorService;
import ca.uhn.fhir.jpa.api.config.JpaStorageSettings;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.interceptor.OverridePathBasedReferentialIntegrityForDeletesInterceptor;
import ca.uhn.fhir.jpa.interceptor.validation.RepositoryValidatingInterceptor;
import ca.uhn.fhir.rest.openapi.OpenApiInterceptor;
import ca.uhn.fhir.rest.server.RestfulServer;
import ca.uhn.fhir.rest.server.interceptor.CaptureResourceSourceFromHeaderInterceptor;
import ca.uhn.fhir.rest.server.interceptor.CorsInterceptor;
import jakarta.servlet.ServletContext;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Optional;
import nl.koppeltaal.hapi.cleanup.ParticipatingApps;
import nl.koppeltaal.hapi.config.FhirServerAuditLogConfiguration;
import nl.koppeltaal.hapi.config.FhirServerSecurityConfiguration;
import nl.koppeltaal.hapi.config.OpenApiConfiguration;
import nl.koppeltaal.hapi.config.SmartBackendServiceConfiguration;
import nl.koppeltaal.hapi.deactivation.ClientDeactivationInterceptor;
import nl.koppeltaal.hapi.interceptor.*;
import nl.koppeltaal.hapi.service.Oauth2AccessTokenService;
import nl.koppeltaal.hapi.util.ServerDeviceId;
import org.hl7.fhir.r4.model.Device;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

/**
 * Registers the Koppeltaal interceptors on the starter's RestfulServer and on HAPI's JPA registry. It runs once all
 * singletons exist, which is before the servlet starts. The starter registered its own interceptors when it created the
 * server, so ours follow them; ServerWiringContractTest pins the resulting order.
 */
public class InterceptorRegistrar implements SmartInitializingSingleton {

  @Autowired private RestfulServer restfulServer;
  @Autowired private IInterceptorService myInterceptorRegistry;
  @Autowired private Oauth2AccessTokenService oauth2AccessTokenService;
  @Autowired private FhirServerSecurityConfiguration fhirServerSecurityConfiguration;
  @Autowired private FhirServerAuditLogConfiguration fhirServerAuditLogConfiguration;
  @Autowired private SmartBackendServiceConfiguration smartBackendServiceConfiguration;
  @Autowired private AuditEventInterceptor auditEventInterceptor;
  @Autowired private InjectCorrelationIdInterceptor injectCorrelationIdInterceptor;
  @Autowired private InjectTraceIdInterceptor injectTraceIdInterceptor;
  @Autowired private AuditEventSubscriptionInterceptor auditEventSubscriptionInterceptor;
  @Autowired private AuditEventIntergityInterceptor auditEventIntergityInterceptor;
  @Autowired private OpenApiConfiguration openApiConfiguration;
  @Autowired private CapabilityStatementInterceptor capabilityStatementInterceptor;
  @Autowired private DaoRegistry daoRegistry;
  @Autowired private JpaStorageSettings jpaStorageSettings;
  @Autowired private OverridePathBasedReferentialIntegrityForDeletesInterceptor referentialIntegrityDeleteInterceptor;
  @Autowired private MimeTypeInterceptor mimeTypeInterceptor;
  @Autowired private JsonSyntaxExceptionInterceptor jsonSyntaxExceptionInterceptor;
  @Autowired private EnforceHttpsSubscriptionEndpointInterceptor enforceHttpsSubscriptionEndpointInterceptor;
  @Autowired private Optional<DeleteFlowLifecycleAuditInterceptor> deleteFlowLifecycleAuditInterceptor;
  @Autowired private ClientDeactivationInterceptor clientDeactivationInterceptor;
  @Autowired private Optional<ResourceOriginCascadingDeleteInterceptor> cascadingDeleteInterceptor;
  @Autowired private FhirServletMapping fhirServletMapping;
  @Autowired @Qualifier("koppeltaalCorsInterceptor") private Optional<CorsInterceptor> koppeltaalCorsInterceptor;
  @Autowired @Qualifier("koppeltaalRepositoryValidatingInterceptor")
  private Optional<RepositoryValidatingInterceptor> koppeltaalRepositoryValidatingInterceptor;
  @Autowired private ServletContext servletContext;

  @Override
  public void afterSingletonsInstantiated() {
    // The starter has registered its interceptors and Tomcat has mapped the servlets: check them before adding ours, so a
    // starter switch set next to ours stops the startup with its own message.
    Collection<Object> koppeltaalInterceptors = new ArrayList<>();
    koppeltaalCorsInterceptor.ifPresent(koppeltaalInterceptors::add);
    koppeltaalRepositoryValidatingInterceptor.ifPresent(koppeltaalInterceptors::add);
    WiringAssertions.verify(restfulServer, myInterceptorRegistry, servletContext, fhirServletMapping.remappedRegistrations(),
      koppeltaalInterceptors);
    // First in our sequence. On its only pointcut shared with the starter's interceptors, SERVER_OUTGOING_RESPONSE, its
    // hook has order -1, so the cascade message stays ahead of the request validator's messages.
    cascadingDeleteInterceptor.ifPresent(restfulServer::registerInterceptor);
    registerKoppeltaalInterceptors();
  }

  private void registerKoppeltaalInterceptors() {
    restfulServer.registerInterceptor(mimeTypeInterceptor);
    restfulServer.registerInterceptor(jsonSyntaxExceptionInterceptor);

    IFhirResourceDao<Device> deviceDao = daoRegistry.getResourceDao(Device.class);
    FhirServerAuditLogConfiguration.Identifier observer = fhirServerAuditLogConfiguration.getObserver().getIdentifier();
    ServerDeviceId serverDeviceId = new ServerDeviceId(
      new ParticipatingApps(daoRegistry, observer.getSystem(), observer.getValue()));
    if (fhirServerSecurityConfiguration.isEnabled()) {
      restfulServer.registerInterceptor(new JwtSecurityInterceptor(oauth2AccessTokenService));
      restfulServer.registerInterceptor(clientDeactivationInterceptor); // refuses inactive clients right after the token check
      restfulServer.registerInterceptor(
        new InjectResourceOriginInterceptor(daoRegistry, deviceDao, smartBackendServiceConfiguration));
      restfulServer.registerInterceptor(
        new ResourceOriginAuthorizationInterceptor(daoRegistry, deviceDao, smartBackendServiceConfiguration));
      restfulServer.registerInterceptor(
        new ResourceOriginSearchNarrowingInterceptor(daoRegistry, deviceDao, jpaStorageSettings, serverDeviceId));
      restfulServer.registerInterceptor(
        new ResourceOriginResponseFilterInterceptor(daoRegistry, smartBackendServiceConfiguration));
    }
    // Subscription narrowing needs both registries: the RestfulServer one and the JPA one do different things.
    SubscriptionNarrowingInterceptor subscriptionNarrowingInterceptor = new SubscriptionNarrowingInterceptor(daoRegistry);
    myInterceptorRegistry.registerInterceptor(subscriptionNarrowingInterceptor);
    restfulServer.registerInterceptor(subscriptionNarrowingInterceptor);

    if (fhirServerAuditLogConfiguration.isEnabled()) {
      restfulServer.registerInterceptor(auditEventInterceptor);
      restfulServer.registerInterceptor(injectCorrelationIdInterceptor);
      restfulServer.registerInterceptor(injectTraceIdInterceptor);
      myInterceptorRegistry.registerInterceptor(injectTraceIdInterceptor);
      myInterceptorRegistry.registerInterceptor(auditEventSubscriptionInterceptor);
      restfulServer.registerInterceptor(auditEventSubscriptionInterceptor);
      restfulServer.registerInterceptor(auditEventIntergityInterceptor);
    }

    restfulServer.registerInterceptor(capabilityStatementInterceptor);
    restfulServer.registerInterceptor(new EnforceIfMatchHeaderInterceptor());
    restfulServer.registerInterceptor(new Oauth2UrisStatementInterceptorForR4(fhirServerSecurityConfiguration));
    // Lets clients set meta.source with the X-Request-Source header
    restfulServer.registerInterceptor(new CaptureResourceSourceFromHeaderInterceptor(restfulServer.getFhirContext()));

    if (openApiConfiguration.isEnabled()) {
      restfulServer.registerInterceptor(new OpenApiInterceptor());
    }

    // AuditEvent.entity.what keeps no referential integrity on delete
    referentialIntegrityDeleteInterceptor.addPath("AuditEvent.entity.what");
    restfulServer.registerInterceptor(referentialIntegrityDeleteInterceptor);

    restfulServer.registerInterceptor(new DefaultDescendingSortInterceptor());
    restfulServer.registerInterceptor(new TaskValidationInterceptor(restfulServer.getFhirContext()));
    restfulServer.registerInterceptor(new DeleteFlowMarkerInterceptor());
    restfulServer.registerInterceptor(enforceHttpsSubscriptionEndpointInterceptor);

    // Storage pointcuts reach both registries: register on one of them only, or every lifecycle event is written twice.
    deleteFlowLifecycleAuditInterceptor.ifPresent(myInterceptorRegistry::registerInterceptor);
  }
}
