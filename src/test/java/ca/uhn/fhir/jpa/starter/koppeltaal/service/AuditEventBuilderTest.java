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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
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
  
  static Stream<Arguments> eventTypeActionAndSubtype() {
    return Stream.of(
      Arguments.of(AuditEventDto.EventType.Create, "C", AuditEventBuilder.CODING_REST, AuditEventBuilder.CODING_INTERACTION_CREATE),
      Arguments.of(AuditEventDto.EventType.Read, "R", AuditEventBuilder.CODING_REST, AuditEventBuilder.CODING_INTERACTION_READ),
      Arguments.of(AuditEventDto.EventType.Update, "U", AuditEventBuilder.CODING_REST, AuditEventBuilder.CODING_INTERACTION_UPDATE),
      Arguments.of(AuditEventDto.EventType.Delete, "D", AuditEventBuilder.CODING_REST, AuditEventBuilder.CODING_INTERACTION_DELETE),
      Arguments.of(AuditEventDto.EventType.Search, "E", AuditEventBuilder.CODING_REST, AuditEventBuilder.CODING_INTERACTION_SEARCH),
      Arguments.of(AuditEventDto.EventType.Capability, "R", AuditEventBuilder.CODING_REST, AuditEventBuilder.CODING_INTERACTION_CAPABILITIES),
      Arguments.of(AuditEventDto.EventType.SendNotification, "E", AuditEventBuilder.CODING_TRANSMIT, null)
    );
  }

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


  @ParameterizedTest
  @MethodSource("eventTypeActionAndSubtype")
  public void testEventTypeMapping(AuditEventDto.EventType eventType, String expectedAction, Coding expectedType, Coding expectedSubtype) {
    AuditEventDto dto = new AuditEventDto();
    dto.setEventType(eventType);
    AuditEvent event = auditEventBuilder.build(dto);
    assert event.getType().equalsShallow(expectedType);
    assert StringUtils.equals(event.getAction().toCode(), expectedAction);
    if (expectedSubtype != null) {
      assert event.getSubtype().get(0).equalsShallow(expectedSubtype);
    } else {
      assert event.getSubtype().isEmpty();
    }
  }

  @Test
  public void testDeleteEntityType() {
    AuditEventDto dto = new AuditEventDto();
    dto.setEventType(AuditEventDto.EventType.Delete);
    Task task = new Task();
    task.setId(new IdType("Task", "123"));
    dto.addResource(new Reference(task));

    AuditEvent event = auditEventBuilder.build(dto);
    assert event.getType().equalsShallow(AuditEventBuilder.CODING_REST);
    assert event.getSubtype().get(0).equalsShallow(AuditEventBuilder.CODING_INTERACTION_DELETE);
    assert StringUtils.equals(event.getAction().toCode(), "D");
    // Verify entity.type is the actual resource type, not OperationOutcome
    AuditEvent.AuditEventEntityComponent entity = event.getEntity().get(0);
    assert StringUtils.equals(entity.getType().getCode(), "Task");
    // entity.what should be empty for delete operations
    assert entity.getWhat().isEmpty();
  }

  @Test
  public void testSearchEntityStructure() {
    AuditEventDto dto = new AuditEventDto();
    dto.setEventType(AuditEventDto.EventType.Search);
    dto.setQuery("/Task?status=active");
    dto.addResource(new Reference(new Task().setId(new IdType("Task", "1"))));
    dto.addResource(new Reference(new Task().setId(new IdType("Task", "2"))));

    AuditEvent event = auditEventBuilder.build(dto);
    List<AuditEvent.AuditEventEntityComponent> entities = event.getEntity();
    assertEquals(3, entities.size());

    // First entity: query entity with only type, role and query
    AuditEvent.AuditEventEntityComponent queryEntity = entities.get(0);
    assertEquals("http://hl7.org/fhir/resource-types", queryEntity.getType().getSystem());
    assertEquals("Task", queryEntity.getType().getCode());
    assertEquals("Task", queryEntity.getType().getDisplay());
    assertEquals("http://terminology.hl7.org/CodeSystem/object-role", queryEntity.getRole().getSystem());
    assertEquals("24", queryEntity.getRole().getCode());
    assertEquals("Query", queryEntity.getRole().getDisplay());
    assertEquals("/Task?status=active", new String(queryEntity.getQuery(), StandardCharsets.UTF_8));
    assertTrue(queryEntity.getWhat().isEmpty());
    assertNull(queryEntity.getName());

    // Result entities: no query, no role
    for (int i = 1; i <= 2; i++) {
      AuditEvent.AuditEventEntityComponent resultEntity = entities.get(i);
      assertNull(resultEntity.getQuery());
      assertTrue(resultEntity.getRole().isEmpty());
    }
  }

  @Test
  public void testReadEntityHasNoQuery() {
    AuditEventDto dto = new AuditEventDto();
    dto.setEventType(AuditEventDto.EventType.Read);
    dto.setQuery("/Patient/123");
    dto.addResource(new Reference(new Patient().setId(new IdType("Patient", "123"))));

    AuditEvent event = auditEventBuilder.build(dto);
    List<AuditEvent.AuditEventEntityComponent> entities = event.getEntity();
    assertEquals(1, entities.size());
    assertNull(entities.get(0).getQuery());
  }

  @Test
  public void testCreateEntityHasNoQuery() {
    AuditEventDto dto = new AuditEventDto();
    dto.setEventType(AuditEventDto.EventType.Create);
    dto.setQuery("/Patient");
    dto.addResource(new Reference(new Patient().setId(new IdType("Patient", "1"))));

    AuditEvent event = auditEventBuilder.build(dto);
    List<AuditEvent.AuditEventEntityComponent> entities = event.getEntity();
    assertEquals(1, entities.size());
    assertNull(entities.get(0).getQuery());
  }

  @Test
  public void testSendNotificationSubscriptionEntity() {
    AuditEventDto dto = new AuditEventDto();
    dto.setEventType(AuditEventDto.EventType.SendNotification);
    dto.setQuery("Task?status=active");

    // Entity 1: the delivered resource
    Task task = new Task();
    task.setId(new IdType("Task", "1"));
    dto.addResource(new Reference(task));

    // Entity 2: the Subscription with versioned reference
    Reference subscriptionRef = new Reference();
    subscriptionRef.setReference("Subscription/42/_history/3");
    subscriptionRef.setType("Subscription");
    dto.addResource(subscriptionRef);

    AuditEvent event = auditEventBuilder.build(dto);
    List<AuditEvent.AuditEventEntityComponent> entities = event.getEntity();
    assertEquals(2, entities.size());

    // Entity 1: delivered resource, no role, no query
    AuditEvent.AuditEventEntityComponent entity1 = entities.get(0);
    assertEquals("Task", entity1.getType().getCode());
    assertNull(entity1.getQuery());
    assertTrue(entity1.getRole().isEmpty());

    // Entity 2: Subscription with role Subscriber and query
    AuditEvent.AuditEventEntityComponent entity2 = entities.get(1);
    assertEquals("Subscription", entity2.getType().getCode());
    assertEquals("Subscription/42/_history/3", entity2.getWhat().getReference());
    assertEquals("http://terminology.hl7.org/CodeSystem/object-role", entity2.getRole().getSystem());
    assertEquals("9", entity2.getRole().getCode());
    assertEquals("Subscriber", entity2.getRole().getDisplay());
    assertEquals("Task?status=active", new String(entity2.getQuery(), StandardCharsets.UTF_8));
  }

  @Test
  public void testExtractResourceTypeFromQuery() {
    assertEquals("Patient", AuditEventBuilder.extractResourceTypeFromQuery("/Patient?resource-origin=x"));
    assertEquals("Patient", AuditEventBuilder.extractResourceTypeFromQuery("Patient?resource-origin=x"));
    assertEquals("Patient", AuditEventBuilder.extractResourceTypeFromQuery("/Patient/123"));
    assertEquals("Patient", AuditEventBuilder.extractResourceTypeFromQuery("Patient/123"));
    assertEquals("Task", AuditEventBuilder.extractResourceTypeFromQuery("/Task?status=active&_count=10"));
    assertEquals("Task", AuditEventBuilder.extractResourceTypeFromQuery("/Task/456/_history/2"));
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
