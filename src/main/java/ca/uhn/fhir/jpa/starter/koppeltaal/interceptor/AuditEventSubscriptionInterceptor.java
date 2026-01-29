package ca.uhn.fhir.jpa.starter.koppeltaal.interceptor;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.interceptor.model.RequestPartitionId;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.starter.koppeltaal.dto.AuditEventDto;
import ca.uhn.fhir.jpa.starter.koppeltaal.service.AuditEventBuilder;
import ca.uhn.fhir.jpa.starter.koppeltaal.service.AuditEventService;
import ca.uhn.fhir.jpa.starter.koppeltaal.util.RequestIdHolder;
import ca.uhn.fhir.jpa.starter.koppeltaal.util.ResourceOriginUtil;
import ca.uhn.fhir.jpa.subscription.model.CanonicalSubscription;
import ca.uhn.fhir.jpa.subscription.model.ResourceDeliveryMessage;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import org.apache.commons.lang3.StringUtils;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r4.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.List;
import java.util.Optional;

import static ca.uhn.fhir.jpa.starter.koppeltaal.interceptor.InjectCorrelationIdInterceptor.CORRELATION_HEADER_KEY;
import static ca.uhn.fhir.jpa.starter.koppeltaal.interceptor.InjectTraceIdInterceptor.TRACE_ID_HEADER_KEY;
import static ca.uhn.fhir.rest.api.Constants.HEADER_REQUEST_ID;

/**
 *
 */
@Component
@Interceptor
public class AuditEventSubscriptionInterceptor extends AbstractAuditEventInterceptor {
  private static final Logger LOG = LoggerFactory.getLogger(AuditEventSubscriptionInterceptor.class);
  protected final FhirContext fhirContext;
  private final RequestIdHolder requestIdHolder;
  private final IFhirResourceDao<Subscription> subscriptionDao;

  public AuditEventSubscriptionInterceptor(DaoRegistry daoRegistry, AuditEventService auditEventService,
                                           FhirContext fhirContext, RequestIdHolder requestIdHolder) {
    super(auditEventService, daoRegistry);
    this.fhirContext = fhirContext;
    this.requestIdHolder = requestIdHolder;
    this.subscriptionDao = daoRegistry.getSubscriptionDao();
  }

  @Hook(value = Pointcut.SUBSCRIPTION_AFTER_DELIVERY, order = Integer.MAX_VALUE)
  public void outgoingSubscriptionSucceeded(ResourceDeliveryMessage message) {
    createSubscriptionAuditEvent(message, null);
  }

  @Hook(value = Pointcut.SUBSCRIPTION_AFTER_DELIVERY_FAILED, order = Integer.MAX_VALUE)
  public void outgoingSubscriptionFailed(ResourceDeliveryMessage message, Exception exception) {
    createSubscriptionAuditEvent(message, exception);
  }

  private void createSubscriptionAuditEvent(ResourceDeliveryMessage message, Exception exception) {

    CanonicalSubscription canonicalSubscription = message.getSubscription();
    // Firstly, retrieve the tracing headers from the actual delivered message, so we know for sure that they match what has been sent
    List<String> headers = canonicalSubscription.getHeaders();

    String traceId = headers.stream()
      .filter(head -> head.startsWith(TRACE_ID_HEADER_KEY))
      .map(keyValue -> StringUtils.substringAfter(keyValue, TRACE_ID_HEADER_KEY + ": "))
      .findFirst().orElseThrow(() -> new IllegalStateException("No trace-id found"));

    String requestId = headers.stream()
      .filter(head -> head.startsWith(HEADER_REQUEST_ID))
      .map(keyValue -> StringUtils.substringAfter(keyValue, HEADER_REQUEST_ID + ": "))
      .findFirst().orElseThrow(() -> new IllegalStateException("No request-id found"));

    Optional<String> correlationIdOptional = headers.stream()
      .filter(head -> head.startsWith(CORRELATION_HEADER_KEY))
      .map(keyValue -> StringUtils.substringAfter(keyValue, CORRELATION_HEADER_KEY + ": "))
      .findFirst();

    LOG.info("Delivered subscription for traceId {}. Creating AuditEvent", traceId);


    LOG.info("Starting AuditEvent creation for subscription with trace id [{}]", traceId);

    Resource resource = (Resource) message.getPayload(fhirContext);

    if (!(resource instanceof AuditEvent)) {
      AuditEventDto dto = new AuditEventDto();
      dto.setEventType(AuditEventDto.EventType.SendNotification);
      dto.setTraceId(traceId);
      dto.setRequestId(requestId);
      correlationIdOptional
        .ifPresent(dto::setCorrelationId);

      if (exception != null) {
        dto.setOutcome("4");
        dto.setOutcomeDesc("Failed to deliver webhook notification. Error message: " + exception.getMessage());
      } else {
        dto.setOutcome("0");
      }
      dto.addResource(new Reference(resource));
      dto.setQuery(canonicalSubscription.getCriteriaString());
      dto.setDateTime(new Date());

      Optional<IdType> resourceOriginDeviceId = correlationIdOptional.isPresent()
        ? requestIdHolder.getRequestingDeviceIdType(correlationIdOptional.get())
        : Optional.empty();

      resourceOriginDeviceId
        .ifPresent(id -> dto.addAgent(new Reference(id), AuditEventBuilder.CODING_SOURCE_ROLE_ID, true));

      SystemRequestDetails requestDetails = newSystemRequestDetails();

      IIdType subscriptionId = canonicalSubscription.getIdElement(fhirContext);
      Subscription subscription = subscriptionDao.read(subscriptionId, requestDetails, true);
      if (subscription != null) {
        Optional<IIdType> subscriptionDeviceId = ResourceOriginUtil.getResourceOriginDeviceId(subscription);
        String endpoint = subscription.getChannel() != null ? subscription.getChannel().getEndpoint() : null;
        subscriptionDeviceId
          .ifPresent(id -> dto.addAgent(new Reference(id), AuditEventBuilder.CODING_DESTINATION_ROLE_ID, false, endpoint));
        dto.addResource(new Reference(subscription));
      }

      LOG.info("Creating: {}", dto);

      auditEventService.submitAuditEvent(dto, requestDetails);
    } else {
      LOG.debug("Not creating AuditEvent as the resource is an instance of AuditEvent");
    }
  }

  private SystemRequestDetails newSystemRequestDetails() {
    return
      new SystemRequestDetails()
        .setRequestPartitionId(RequestPartitionId.defaultPartition());
  }
}
