package ca.uhn.fhir.jpa.starter.koppeltaal.interceptor;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.api.model.DaoMethodOutcome;
import ca.uhn.fhir.jpa.starter.koppeltaal.config.FhirServerAuditLogConfiguration;
import ca.uhn.fhir.jpa.starter.koppeltaal.service.AuditEventBuilder;
import ca.uhn.fhir.jpa.starter.koppeltaal.service.AuditEventService;
import ca.uhn.fhir.jpa.starter.koppeltaal.util.RequestIdHolder;
import ca.uhn.fhir.jpa.starter.koppeltaal.util.ResourceOriginUtil;
import ca.uhn.fhir.jpa.subscription.model.CanonicalSubscription;
import ca.uhn.fhir.jpa.subscription.model.ResourceDeliveryMessage;
import ca.uhn.fhir.rest.api.EncodingEnum;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.SimpleBundleProvider;
import org.apache.commons.lang3.StringUtils;
import org.apache.lucene.util.SameThreadExecutorService;
import org.hl7.fhir.r4.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static ca.uhn.fhir.jpa.starter.koppeltaal.interceptor.InjectTraceIdInterceptor.TRACE_ID_HEADER_KEY;
import static ca.uhn.fhir.rest.api.Constants.HEADER_REQUEST_ID;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class AuditEventSubscriptionInterceptorTest {
  AuditEventSubscriptionInterceptor interceptor;

  String currentTraceId;
  String currentRequestId;

  Device sourceDevice;

  FhirContext fhirContext;

  @Mock
  IFhirResourceDao auditEventDao;
  @Mock
  IFhirResourceDao subscriptionDao;

  @Mock
  IFhirResourceDao deviceDao;

  @BeforeEach
  void init(@Mock DaoRegistry daoRegistry) {
    when(daoRegistry.getSubscriptionDao()).thenReturn(subscriptionDao);
    when(daoRegistry.getResourceDao(Device.class)).thenReturn(deviceDao);
    when(daoRegistry.getResourceDao(AuditEvent.class)).thenReturn(auditEventDao);

    when(deviceDao.search(any(), any())).thenReturn(new SimpleBundleProvider());

    {
      DaoMethodOutcome outcome = new DaoMethodOutcome();
      sourceDevice = setBaseValues(new Device());
      outcome.setResource(sourceDevice);
      when(deviceDao.update(any(), any(RequestDetails.class))).thenReturn(outcome);
    }
    {
      DaoMethodOutcome outcome = new DaoMethodOutcome();
      AuditEvent auditEvent = setBaseValues(new AuditEvent());
      outcome.setResource(auditEvent);
      outcome.setCreated(true);
      when(auditEventDao.create(any(), any(RequestDetails.class))).thenReturn(outcome);
    }

    {
      Subscription subscription = setBaseValues(new Subscription());
      when(subscriptionDao.read(any(), any(RequestDetails.class), eq(true))).thenReturn(subscription);
    }

    FhirServerAuditLogConfiguration fhirServerAuditLogConfiguration = new FhirServerAuditLogConfiguration();
    AuditEventBuilder auditEventBuilder = new AuditEventBuilder(daoRegistry, fhirServerAuditLogConfiguration);
    auditEventBuilder.init();

    AuditEventService auditEventService = new AuditEventService(daoRegistry, auditEventBuilder) {
      @Override
      public void init() {
        super.executorService = new SameThreadExecutorService();
        super.sleepTime = 0;
      }
    };
    auditEventService.init();
    RequestIdHolder requestIdHolder = new RequestIdHolder();
    currentTraceId = UUID.randomUUID().toString();
    currentRequestId = UUID.randomUUID().toString();
    Device requestingDevice = new Device();
    requestingDevice.setId("req-dev-id");
    requestIdHolder.addMapping(currentTraceId, currentRequestId, Optional.of(requestingDevice));
    fhirContext = FhirContext.forR4();

    interceptor = new AuditEventSubscriptionInterceptor(daoRegistry, auditEventService, fhirContext, requestIdHolder);
  }

  private <T extends Resource> T setBaseValues(T resource) {
    resource.setId(new IdType(resource.getClass().getSimpleName(), UUID.randomUUID().toString()));
    return resource;
  }

  @Test
  void outgoingSubscriptionSuccess() {
    ResourceDeliveryMessage message = new ResourceDeliveryMessage();
    Patient patient = setBaseValues(new Patient());
    patient.addExtension(ResourceOriginUtil.RESOURCE_ORIGIN_SYSTEM, new Reference(sourceDevice));
    message.setPayload(fhirContext, patient, EncodingEnum.JSON);
    message.setTransactionId(currentTraceId);
    CanonicalSubscription subscription = new CanonicalSubscription();
    subscription.setIdElement(new IdType("Subscription", UUID.randomUUID().toString()));

    subscription.addHeader(TRACE_ID_HEADER_KEY + ": " + currentTraceId);
    subscription.addHeader(HEADER_REQUEST_ID + ": " + currentRequestId);

    message.setSubscription(subscription);
    interceptor.outgoingSubscriptionSucceeded(message);

    ArgumentCaptor<AuditEvent> argument = ArgumentCaptor.forClass(AuditEvent.class);
    verify(auditEventDao, atLeastOnce()).create(argument.capture(), any(RequestDetails.class));
    AuditEvent value = argument.getValue();
    assert value.getAction() == AuditEvent.AuditEventAction.E;
    assert value.getType().equalsShallow(AuditEventBuilder.CODING_TRANSMIT);
//    assert !value.getAgent().isEmpty();
    assert "0".equals(value.getOutcome().toCode());
//    assert "req-dev-id".equals(value.getAgent().get(0).getWho().getReference());//TODO: Fix, only sent on correlation-id

    String traceIdOnSubscription = value.getExtensionByUrl("http://koppeltaal.nl/fhir/StructureDefinition/trace-id").getValue().toString();
    assert currentTraceId.equals(traceIdOnSubscription);
    String requestIdOnSubscription = value.getExtensionByUrl("http://koppeltaal.nl/fhir/StructureDefinition/request-id").getValue().toString();
    assert currentRequestId.equals(requestIdOnSubscription);

    // String text =
    // fhirContext.newJsonParser().setPrettyPrint(true).encodeResourceToString(value);

  }

  @Test
  void outgoingSubscriptionFailure() {
    ResourceDeliveryMessage message = new ResourceDeliveryMessage();
    Patient patient = setBaseValues(new Patient());
    patient.addExtension(ResourceOriginUtil.RESOURCE_ORIGIN_SYSTEM, new Reference(sourceDevice));
    message.setPayload(fhirContext, patient, EncodingEnum.JSON);
    message.setTransactionId(currentTraceId);
    CanonicalSubscription subscription = new CanonicalSubscription();
    subscription.setIdElement(new IdType("Subscription", UUID.randomUUID().toString()));
    subscription.addHeader(TRACE_ID_HEADER_KEY + ": " + currentTraceId);
    subscription.addHeader(HEADER_REQUEST_ID + ": " + currentRequestId);
    message.setSubscription(subscription);

    interceptor.outgoingSubscriptionFailed(message, new Exception("My error message"));

    ArgumentCaptor<AuditEvent> argument = ArgumentCaptor.forClass(AuditEvent.class);
    verify(auditEventDao, atLeastOnce()).create(argument.capture(), any(RequestDetails.class));
    AuditEvent value = argument.getValue();
    assert value.getAction() == AuditEvent.AuditEventAction.E;
    assert value.getType().equalsShallow(AuditEventBuilder.CODING_TRANSMIT);
//    assert !value.getAgent().isEmpty();
    assert "4".equals(value.getOutcome().toCode());
    assert StringUtils.isNotBlank(value.getOutcomeDesc());
    //    assert "req-dev-id".equals(value.getAgent().get(0).getWho().getReference());//TODO: Fix, only sent on correlation-id

    // String text =
    // fhirContext.newJsonParser().setPrettyPrint(true).encodeResourceToString(value);

  }
}
