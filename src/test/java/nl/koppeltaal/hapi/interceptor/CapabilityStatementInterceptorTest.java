package nl.koppeltaal.hapi.interceptor;

import org.hl7.fhir.r4.model.CapabilityStatement;
import org.hl7.fhir.r4.model.CapabilityStatement.CapabilityStatementRestComponent;
import org.hl7.fhir.r4.model.CapabilityStatement.CapabilityStatementRestResourceComponent;
import org.hl7.fhir.r4.model.CapabilityStatement.ResourceInteractionComponent;
import org.hl7.fhir.r4.model.CapabilityStatement.SystemRestfulInteraction;
import org.hl7.fhir.r4.model.CapabilityStatement.TypeRestfulInteraction;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Properties;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CapabilityStatementInterceptorTest {

  @Test
  void shouldOnlyAdvertiseTheSupportedInteractions() {
    CapabilityStatement capabilityStatement = new CapabilityStatement();
    CapabilityStatementRestComponent rest = capabilityStatement.addRest();
    rest.addInteraction().setCode(SystemRestfulInteraction.TRANSACTION);
    rest.addInteraction().setCode(SystemRestfulInteraction.HISTORYSYSTEM);
    rest.addOperation().setName("reindex");

    CapabilityStatementRestResourceComponent patient = rest.addResource().setType("Patient");
    patient.addInteraction().setCode(TypeRestfulInteraction.READ);
    patient.addInteraction().setCode(TypeRestfulInteraction.HISTORYINSTANCE);
    patient.addInteraction().setCode(TypeRestfulInteraction.HISTORYTYPE);
    patient.addInteraction().setCode(TypeRestfulInteraction.PATCH);
    patient.setConditionalCreate(true);
    patient.addSearchInclude("Patient:general-practitioner");
    patient.addSearchRevInclude("Task:patient");
    patient.addOperation().setName("validate");
    patient.addOperation().setName("everything");
    patient.addOperation().setName("meta-add");

    CapabilityStatementRestResourceComponent valueSet = rest.addResource().setType("ValueSet");
    valueSet.addOperation().setName("expand");
    valueSet.addOperation().setName("validate-code");
    valueSet.addOperation().setName("validate");

    new CapabilityStatementInterceptor().customize(capabilityStatement);

    assertTrue(rest.getInteraction().isEmpty(), "No system level interactions are supported");
    assertTrue(rest.getOperation().isEmpty(), "No system level operations are offered");

    assertEquals(List.of(TypeRestfulInteraction.READ, TypeRestfulInteraction.HISTORYINSTANCE),
      patient.getInteraction().stream().map(ResourceInteractionComponent::getCode).collect(Collectors.toList()));
    assertFalse(patient.getConditionalCreate());
    assertEquals(List.of("validate"), operationNames(patient));
    assertEquals(1, patient.getSearchInclude().size(), "_include is supported");
    assertTrue(patient.getSearchRevInclude().isEmpty(), "_revinclude is not supported");

    assertEquals(List.of("expand", "validate-code", "validate"), operationNames(valueSet));
  }

  /** The date identifies the deployed build. The image's WAR is upstream's, so the extension reports its own build time. */
  @Test
  void shouldReportTheBuildTimeOfTheExtension() throws Exception {
    String buildTime = extensionBuildTime();
    CapabilityStatement capabilityStatement = new CapabilityStatement();
    capabilityStatement.setDate(Date.from(Instant.parse("2025-07-10T08:29:35Z")));

    new CapabilityStatementInterceptor().customize(capabilityStatement);

    assertEquals(Instant.parse(buildTime), capabilityStatement.getDate().toInstant());
  }

  static String extensionBuildTime() throws Exception {
    Properties buildInfo = new Properties();
    try (InputStream in = CapabilityStatementInterceptor.class.getResourceAsStream("/nl/koppeltaal/hapi/build-info.properties")) {
      assertNotNull(in, "the build writes nl/koppeltaal/hapi/build-info.properties");
      buildInfo.load(in);
    }
    String buildTime = buildInfo.getProperty("build-time");
    assertTrue(buildTime.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z"), "filtered build time " + buildTime);
    return buildTime;
  }

  private static List<String> operationNames(CapabilityStatementRestResourceComponent resource) {
    return resource.getOperation().stream()
      .map(CapabilityStatement.CapabilityStatementRestResourceOperationComponent::getName)
      .collect(Collectors.toList());
  }
}
