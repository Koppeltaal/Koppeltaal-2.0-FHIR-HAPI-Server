package ca.uhn.fhir.jpa.starter.koppeltaal.controller;

import ca.uhn.fhir.jpa.starter.koppeltaal.config.SmartConfigurationProperties;
import ca.uhn.fhir.jpa.starter.koppeltaal.dto.SmartConfigurationDto;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SmartConfigurationController {

  private final String smartConfiguration;

  public SmartConfigurationController(SmartConfigurationProperties smartConfigurationProperties) {
    SmartConfigurationDto smartConfigurationDto = new SmartConfigurationDto(smartConfigurationProperties);
    ObjectMapper objectMapper = new ObjectMapper();
    try {
      smartConfiguration = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(smartConfigurationDto);
    } catch (JsonProcessingException e) {
      throw new RuntimeException("Failed to transform the SMART conformance", e);
    }
  }

  @GetMapping(value = "/smart-configuration", headers = "Accept=*/*", produces = "application/json")
  public String getSmartConfiguration() {
    return smartConfiguration;
  }
}
