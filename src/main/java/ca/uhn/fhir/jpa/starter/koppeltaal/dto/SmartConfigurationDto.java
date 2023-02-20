package ca.uhn.fhir.jpa.starter.koppeltaal.dto;

import ca.uhn.fhir.jpa.starter.koppeltaal.config.SmartConfigurationProperties;
import org.springframework.beans.BeanUtils;

/**
 * Non-bean variant of the {@link SmartConfigurationProperties} that can easily be serialized by Jackson as it's not a Bean
 */

public class SmartConfigurationDto extends SmartConfigurationProperties {

  public SmartConfigurationDto(SmartConfigurationProperties smartConfigurationProperties) {
    BeanUtils.copyProperties(smartConfigurationProperties, this);
  }
}
