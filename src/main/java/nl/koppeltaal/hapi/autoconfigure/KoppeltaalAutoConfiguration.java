package nl.koppeltaal.hapi.autoconfigure;

import nl.koppeltaal.hapi.bean.ConditionalGraphQLConfiguration;
import nl.koppeltaal.hapi.cleanup.CleanupConfig;
import nl.koppeltaal.hapi.cleanup.CleanupProperties;
import nl.koppeltaal.hapi.config.BeanOverrideConfig;
import nl.koppeltaal.hapi.config.DaoResourceLinkResolverOverrideRegistrar;
import nl.koppeltaal.hapi.config.FhirServerAuditLogConfiguration;
import nl.koppeltaal.hapi.config.FhirServerSecurityConfiguration;
import nl.koppeltaal.hapi.config.OpenApiConfiguration;
import nl.koppeltaal.hapi.config.PatientAssignmentValidationConfig;
import nl.koppeltaal.hapi.config.SmartBackendServiceConfiguration;
import nl.koppeltaal.hapi.config.SmartConfigurationProperties;
import nl.koppeltaal.hapi.config.SubscriptionErrorProperties;
import nl.koppeltaal.hapi.config.SubscriptionRetryProperties;
import nl.koppeltaal.hapi.controller.SmartConfigurationController;
import nl.koppeltaal.hapi.deactivation.ClientDeactivationConfig;
import nl.koppeltaal.hapi.deactivation.ClientDeactivationProperties;
import nl.koppeltaal.hapi.interceptor.AuditEventIntergityInterceptor;
import nl.koppeltaal.hapi.interceptor.AuditEventInterceptor;
import nl.koppeltaal.hapi.interceptor.AuditEventSubscriptionInterceptor;
import nl.koppeltaal.hapi.interceptor.CapabilityStatementInterceptor;
import nl.koppeltaal.hapi.interceptor.EnforceHttpsSubscriptionEndpointInterceptor;
import nl.koppeltaal.hapi.interceptor.InjectCorrelationIdInterceptor;
import nl.koppeltaal.hapi.interceptor.InjectTraceIdInterceptor;
import nl.koppeltaal.hapi.interceptor.JsonSyntaxExceptionInterceptor;
import nl.koppeltaal.hapi.interceptor.MimeTypeInterceptor;
import nl.koppeltaal.hapi.service.AuditEventBuilder;
import nl.koppeltaal.hapi.service.AuditEventService;
import nl.koppeltaal.hapi.service.JwtValidationService;
import nl.koppeltaal.hapi.service.Oauth2AccessTokenService;
import nl.koppeltaal.hapi.service.PackageInstallerWithExclusionSvcImpl;
import nl.koppeltaal.hapi.subscription.SubscriptionErrorConfig;
import nl.koppeltaal.hapi.util.RequestIdHolder;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureOrder;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * The only entry point of the Koppeltaal extension. The starter loads it from /app/extra-classes as a Spring Boot
 * auto-configuration, and it imports every Koppeltaal configuration and component explicitly; nothing is
 * component-scanned. It is ordered first, so Boot's own auto-configurations, such as the task scheduler, see our
 * @EnableScheduling and beans. SmartConfigurationController is imported too: the main dispatcher keeps serving it at
 * /smart-configuration.
 */
@AutoConfiguration
@AutoConfigureOrder(Ordered.HIGHEST_PRECEDENCE)
@EnableScheduling
@Import({
  // configuration
  BeanOverrideConfig.class,
  CleanupConfig.class,
  CleanupProperties.class,
  ClientDeactivationConfig.class,
  ClientDeactivationProperties.class,
  ConditionalGraphQLConfiguration.class,
  DaoResourceLinkResolverOverrideRegistrar.class,
  FhirServerAuditLogConfiguration.class,
  FhirServerSecurityConfiguration.class,
  OpenApiConfiguration.class,
  PatientAssignmentValidationConfig.class,
  SmartBackendServiceConfiguration.class,
  SmartConfigurationProperties.class,
  SubscriptionErrorConfig.class,
  SubscriptionErrorProperties.class,
  SubscriptionRetryProperties.class,
  // components
  AuditEventBuilder.class,
  AuditEventIntergityInterceptor.class,
  AuditEventInterceptor.class,
  AuditEventService.class,
  AuditEventSubscriptionInterceptor.class,
  CapabilityStatementInterceptor.class,
  EnforceHttpsSubscriptionEndpointInterceptor.class,
  InjectCorrelationIdInterceptor.class,
  InjectTraceIdInterceptor.class,
  JsonSyntaxExceptionInterceptor.class,
  JwtValidationService.class,
  MimeTypeInterceptor.class,
  Oauth2AccessTokenService.class,
  PackageInstallerWithExclusionSvcImpl.class,
  RequestIdHolder.class,
  SmartConfigurationController.class,
  // server wiring
  ServletWiringConfig.class
})
public class KoppeltaalAutoConfiguration {
}
