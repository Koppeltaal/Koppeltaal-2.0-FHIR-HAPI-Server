package ca.uhn.fhir.jpa.starter.koppeltaal.interceptor;

import org.hl7.fhir.r4.model.CapabilityStatement;
import org.hl7.fhir.r4.model.CapabilityStatement.CapabilityStatementRestComponent;
import org.hl7.fhir.r4.model.CapabilityStatement.CapabilityStatementRestResourceComponent;
import org.hl7.fhir.r4.model.CapabilityStatement.ResourceInteractionComponent;
import org.hl7.fhir.r4.model.CapabilityStatement.SystemRestfulInteraction;
import org.hl7.fhir.r4.model.CapabilityStatement.TypeRestfulInteraction;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    assertEquals(List.of(TypeRestfulInteraction.READ, TypeRestfulInteraction.HISTORYTYPE),
      patient.getInteraction().stream().map(ResourceInteractionComponent::getCode).collect(Collectors.toList()));
    assertFalse(patient.getConditionalCreate());
    assertEquals(List.of("validate"), operationNames(patient));
    assertEquals(1, patient.getSearchInclude().size(), "_include is supported");
    assertTrue(patient.getSearchRevInclude().isEmpty(), "_revinclude is not supported");

    assertEquals(List.of("expand", "validate-code", "validate"), operationNames(valueSet));
  }

  private static List<String> operationNames(CapabilityStatementRestResourceComponent resource) {
    return resource.getOperation().stream()
      .map(CapabilityStatement.CapabilityStatementRestResourceOperationComponent::getName)
      .collect(Collectors.toList());
  }
}
