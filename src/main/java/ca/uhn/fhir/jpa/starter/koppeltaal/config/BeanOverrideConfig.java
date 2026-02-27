package ca.uhn.fhir.jpa.starter.koppeltaal.config;

import ca.uhn.fhir.jpa.dao.index.DaoResourceLinkResolver;
import ca.uhn.fhir.jpa.starter.koppeltaal.bean.DaoResourceLinkResolverOverride;
import ca.uhn.fhir.jpa.subscription.channel.impl.RetryPolicyProvider;
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

  private final DaoResourceLinkResolverOverride daoResourceLinkResolverOverride;

  public BeanOverrideConfig(DaoResourceLinkResolverOverride daoResourceLinkResolverOverride) {
    this.daoResourceLinkResolverOverride = daoResourceLinkResolverOverride;
  }

  @Primary
  @Bean
  public DaoResourceLinkResolver daoResourceLinkResolver() {
    return daoResourceLinkResolverOverride;
  }

  @Primary
  @Bean
  public RetryPolicyProvider retryPolicyProvider(SubscriptionRetryProperties retryProperties) {
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
