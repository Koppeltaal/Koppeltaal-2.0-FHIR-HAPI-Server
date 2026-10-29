package ca.uhn.fhir.jpa.starter.koppeltaal.config;

import ca.uhn.fhir.jpa.dao.index.DaoResourceLinkResolver;
import ca.uhn.fhir.jpa.model.dao.JpaPid;
import ca.uhn.fhir.jpa.searchparam.extractor.IResourceLinkResolver;
import ca.uhn.fhir.jpa.starter.koppeltaal.bean.DaoResourceLinkResolverOverride;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.core.ResolvableType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DaoResourceLinkResolverOverrideRegistrarTest {

  private DefaultListableBeanFactory registry;

  @BeforeEach
  void init() {
    registry = new DefaultListableBeanFactory();

    // the definition from HAPI's JpaConfig, which won the override by name
    RootBeanDefinition hapiDefinition = new RootBeanDefinition(DaoResourceLinkResolver.class);
    hapiDefinition.setPrimary(true);
    registry.registerBeanDefinition(DaoResourceLinkResolverOverrideRegistrar.BEAN_NAME, hapiDefinition);
  }

  @Test
  void shouldReplaceTheHapiDefinitionWithTheOverride() {
    new DaoResourceLinkResolverOverrideRegistrar().postProcessBeanDefinitionRegistry(registry);

    BeanDefinition definition = registry.getBeanDefinition(DaoResourceLinkResolverOverrideRegistrar.BEAN_NAME);
    assertEquals(DaoResourceLinkResolverOverride.class.getName(), definition.getBeanClassName());
    assertTrue(definition.isPrimary());
  }

  @Test
  void shouldNotDependOnBeanDefinitionOverridingBeingAllowed() {
    registry.setAllowBeanDefinitionOverriding(false);

    new DaoResourceLinkResolverOverrideRegistrar().postProcessBeanDefinitionRegistry(registry);

    assertEquals(DaoResourceLinkResolverOverride.class.getName(),
      registry.getBeanDefinition(DaoResourceLinkResolverOverrideRegistrar.BEAN_NAME).getBeanClassName());
  }

  @Test
  void shouldMatchBothHapiInjectionPoints() {
    new DaoResourceLinkResolverOverrideRegistrar().postProcessBeanDefinitionRegistry(registry);

    // SearchParamExtractorService injects IResourceLinkResolver, the inline references extractor DaoResourceLinkResolver<JpaPid>
    assertTrue(registry.isTypeMatch(DaoResourceLinkResolverOverrideRegistrar.BEAN_NAME, IResourceLinkResolver.class));
    assertTrue(registry.isTypeMatch(DaoResourceLinkResolverOverrideRegistrar.BEAN_NAME,
      ResolvableType.forClassWithGenerics(DaoResourceLinkResolver.class, JpaPid.class)));
  }
}
