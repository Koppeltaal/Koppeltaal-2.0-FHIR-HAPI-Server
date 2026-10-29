package ca.uhn.fhir.jpa.starter.koppeltaal.config;

import ca.uhn.fhir.jpa.model.dao.JpaPid;
import ca.uhn.fhir.jpa.starter.koppeltaal.bean.DaoResourceLinkResolverOverride;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.core.ResolvableType;
import org.springframework.stereotype.Component;

/**
 * Replaces the <code>daoResourceLinkResolver</code> bean of HAPI's <code>JpaConfig</code> with the
 * {@link DaoResourceLinkResolverOverride}.
 *
 * <p>Defining a bean with the same name is not reliable: with bean definition overriding enabled, the definition that
 * is registered last wins, and that turned out to be HAPI's. The override was not used, so installing the Koppeltaal
 * ImplementationGuide on a fresh database failed on its references to example resources (HAPI-1094). This post
 * processor runs after all bean definitions are registered.</p>
 */
@Component
public class DaoResourceLinkResolverOverrideRegistrar implements BeanDefinitionRegistryPostProcessor {

  private static final Logger LOG = LoggerFactory.getLogger(DaoResourceLinkResolverOverrideRegistrar.class);

  static final String BEAN_NAME = "daoResourceLinkResolver";

  @Override
  public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
    final RootBeanDefinition definition = new RootBeanDefinition(DaoResourceLinkResolverOverride.class);
    definition.setTargetType(ResolvableType.forClassWithGenerics(DaoResourceLinkResolverOverride.class, JpaPid.class));
    definition.setPrimary(true);

    if (registry.containsBeanDefinition(BEAN_NAME)) {
      registry.removeBeanDefinition(BEAN_NAME);
    }
    registry.registerBeanDefinition(BEAN_NAME, definition);

    LOG.info("Registered {} as [{}]", DaoResourceLinkResolverOverride.class.getSimpleName(), BEAN_NAME);
  }

  @Override
  public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
    // all work is done on the registry
  }
}
