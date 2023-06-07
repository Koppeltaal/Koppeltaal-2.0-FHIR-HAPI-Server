package ca.uhn.fhir.jpa.starter.koppeltaal.service;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.api.model.DaoMethodOutcome;
import ca.uhn.fhir.jpa.starter.koppeltaal.config.FhirServerAuditLogConfiguration;
import ca.uhn.fhir.jpa.starter.koppeltaal.dto.AuditEventDto;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.SimpleBundleProvider;
import org.apache.commons.lang3.StringUtils;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r4.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;


/**
 *
 */
@ExtendWith(MockitoExtension.class)
public class AuditEventBuilderTest {
  AuditEventBuilder auditEventBuilder;
  @Mock
  IFhirResourceDao deviceDao;

  @BeforeEach
  public void init(@Mock DaoRegistry daoRegistry) {
    when(daoRegistry.getResourceDao(Device.class)).thenReturn(deviceDao);
    when(deviceDao.search(any(), any())).thenReturn(new SimpleBundleProvider());
    {
      DaoMethodOutcome outcome = new DaoMethodOutcome();
      Device sourceDevice = new Device();
      outcome.setResource(sourceDevice);
      when(deviceDao.update(any(), any(RequestDetails.class))).thenReturn(outcome);
    }

    FhirServerAuditLogConfiguration fhirServerAuditLogConfiguration = new FhirServerAuditLogConfiguration();
    auditEventBuilder = new AuditEventBuilder(daoRegistry, fhirServerAuditLogConfiguration);
    auditEventBuilder.init();
  }

  @Test
  public void testMinimal() {
    AuditEventDto dto = new AuditEventDto();
    dto.setEventType(AuditEventDto.EventType.Create);
    AuditEvent event = auditEventBuilder.build(dto);
    assert event.getType().equalsShallow(AuditEventBuilder.CODING_REST);
    assert event.getSubtype().get(0).equalsShallow(AuditEventBuilder.CODING_INTERACTION_CREATE);
    assert StringUtils.equals(event.getAction().toCode(), "C");
    assert event.getAgent().isEmpty();
    assert event.getEntity().isEmpty();
  }

  @Test
  public void testNormal() {
    AuditEventDto dto = new AuditEventDto();
    dto.setEventType(AuditEventDto.EventType.Create);
    dto.addResource(new Reference(new IdType("Kaas", "1")));
    dto.addResource(new Reference(new Patient().setId(new IdType("Patient", "2"))));
    dto.addResource(new Reference(new Patient().setId("Patient/3")));

    AuditEvent event = auditEventBuilder.build(dto);
    assert event.getType().equalsShallow(AuditEventBuilder.CODING_REST);
    assert event.getSubtype().get(0).equalsShallow(AuditEventBuilder.CODING_INTERACTION_CREATE);
    assert StringUtils.equals(event.getAction().toCode(), "C");
    assert event.getAgent().isEmpty();
    assert StringUtils.equals(getReferenceLikeFhirDoes(event.getEntity().get(0).getWhat()), "Kaas/1");
    assert StringUtils.equals(getReferenceLikeFhirDoes(event.getEntity().get(1).getWhat()), "Patient/2");
    assert StringUtils.equals(getReferenceLikeFhirDoes(event.getEntity().get(2).getWhat()), "Patient/3");
  }

  public static String getReferenceLikeFhirDoes(Reference reference) {
    String rv = reference.getReference();
    if (StringUtils.isEmpty(rv)) {
      IBaseResource resource = reference.getResource();
      if (resource != null) {
        IIdType idElement = resource.getIdElement();
        rv = idElement.getValue();
      }
    }
    return rv;
  }
}
