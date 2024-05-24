package ca.uhn.fhir.jpa.starter.koppeltaal.controller;

import ca.uhn.fhir.jpa.starter.koppeltaal.config.SmartConfigurationProperties;
import ca.uhn.fhir.jpa.starter.koppeltaal.dto.SmartConfigurationDto;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.json.MappingJacksonValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SmartConfigurationController {

  private final SmartConfigurationDto smartConfigurationDto;

  public SmartConfigurationController(SmartConfigurationProperties smartConfigurationProperties) {
    this.smartConfigurationDto = new SmartConfigurationDto(smartConfigurationProperties);
  }

  @GetMapping(value = "/smart-configuration", headers = "Accept=*/*")
  public ResponseEntity<MappingJacksonValue> getSmartConfiguration() {
    MappingJacksonValue mappingJacksonValue = new MappingJacksonValue(smartConfigurationDto);
    HttpHeaders headers = new HttpHeaders();
    headers.add(HttpHeaders.CONTENT_TYPE, "application/json");
    return new ResponseEntity<>(mappingJacksonValue, headers, HttpStatus.OK);
  }
}
