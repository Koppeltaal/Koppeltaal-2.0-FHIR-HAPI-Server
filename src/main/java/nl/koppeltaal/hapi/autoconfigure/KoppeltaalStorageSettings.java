package nl.koppeltaal.hapi.autoconfigure;

import ca.uhn.fhir.jpa.api.config.JpaStorageSettings;
import org.springframework.beans.factory.config.BeanPostProcessor;

/**
 * Koppeltaal always assigns UUID resource ids and indexes full text, whatever the configuration says. The fork's code
 * set both on JpaStorageSettings itself; docker/config names the same values for readers.
 */
public class KoppeltaalStorageSettings implements BeanPostProcessor {

  @Override
  public Object postProcessAfterInitialization(Object bean, String beanName) {
    if (bean instanceof JpaStorageSettings settings) {
      settings.setResourceServerIdStrategy(JpaStorageSettings.IdStrategyEnum.UUID);
      settings.setHibernateSearchIndexFullText(true);
    }
    return bean;
  }
}
