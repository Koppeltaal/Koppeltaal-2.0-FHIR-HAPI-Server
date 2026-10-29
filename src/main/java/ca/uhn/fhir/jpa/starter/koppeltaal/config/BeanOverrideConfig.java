package ca.uhn.fhir.jpa.starter.koppeltaal.config;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.api.config.JpaStorageSettings;
import ca.uhn.fhir.jpa.api.svc.IIdHelperService;
import ca.uhn.fhir.jpa.dao.data.IResourceLinkDao;
import ca.uhn.fhir.jpa.dao.expunge.ResourceTableFKProvider;
import ca.uhn.fhir.jpa.delete.batch2.DeleteExpungeSqlBuilder;
import ca.uhn.fhir.jpa.model.config.PartitionSettings;
import ca.uhn.fhir.jpa.model.dao.JpaPid;
import ca.uhn.fhir.jpa.starter.koppeltaal.bean.DeleteExpungeSqlBuilderOverride;
import ca.uhn.fhir.jpa.starter.koppeltaal.bean.ErasureAwareMemoryCacheService;
import ca.uhn.fhir.jpa.subscription.channel.impl.RetryPolicyProvider;
import ca.uhn.fhir.jpa.util.MemoryCacheService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.retry.RetryPolicy;
import org.springframework.retry.policy.NeverRetryPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;

@Configuration
public class BeanOverrideConfig {

  private static final Logger LOG = LoggerFactory.getLogger(BeanOverrideConfig.class);

  // The DaoResourceLinkResolver override is registered by the DaoResourceLinkResolverOverrideRegistrar

  @Bean
  @Primary
  public static MemoryCacheService koppeltaalMemoryCacheService(JpaStorageSettings settings, FhirContext context) {
    return new ErasureAwareMemoryCacheService(settings, context);
  }

  /**
   * Deliberately not named after HAPI's own bean: an identical name would make this a replacement whose winner
   * depends on registration order, where a distinct name plus @Primary decides the injection outright.
   */
  @Primary
  @Bean
  public DeleteExpungeSqlBuilder koppeltaalDeleteExpungeSqlBuilder(ResourceTableFKProvider resourceTableFKProvider,
    JpaStorageSettings storageSettings, IIdHelperService<JpaPid> idHelper, IResourceLinkDao resourceLinkDao,
    PartitionSettings partitionSettings) {

    return new DeleteExpungeSqlBuilderOverride(resourceTableFKProvider, storageSettings, idHelper, resourceLinkDao,
      partitionSettings);
  }

  @Primary
  @Bean
  public RetryPolicyProvider koppeltaalRetryPolicyProvider(SubscriptionRetryProperties retryProperties) {
    int maxAttempts = retryProperties.getMaxAttempts();
    LOG.info("Configuring subscription retry policy with maxAttempts={}", maxAttempts);
    return new RetryPolicyProvider() {
      @Override
      protected RetryPolicy retryPolicy() {
        if (maxAttempts <= 1) {
          return new NeverRetryPolicy();
        }
        return new SimpleRetryPolicy(maxAttempts);
      }
    };
  }
}
