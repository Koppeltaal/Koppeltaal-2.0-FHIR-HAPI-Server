package ca.uhn.fhir.jpa.starter.koppeltaal.config;

import ca.uhn.fhir.jpa.dao.index.DaoResourceLinkResolver;
import ca.uhn.fhir.jpa.starter.koppeltaal.bean.DaoResourceLinkResolverOverride;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class BeanOverrideConfig {

  private final DaoResourceLinkResolverOverride daoResourceLinkResolverOverride;

  public BeanOverrideConfig(DaoResourceLinkResolverOverride daoResourceLinkResolverOverride) {
    this.daoResourceLinkResolverOverride = daoResourceLinkResolverOverride;
  }

  @Primary
  @Bean
  public DaoResourceLinkResolver daoResourceLinkResolver() {
    return daoResourceLinkResolverOverride;
  }
}
