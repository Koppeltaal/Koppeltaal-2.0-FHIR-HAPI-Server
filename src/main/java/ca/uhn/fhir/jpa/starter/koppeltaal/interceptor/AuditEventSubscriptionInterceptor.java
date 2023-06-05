package ca.uhn.fhir.jpa.starter.koppeltaal.interceptor;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.starter.koppeltaal.dto.AuditEventDto;
import ca.uhn.fhir.jpa.starter.koppeltaal.service.AuditEventService;
import ca.uhn.fhir.jpa.starter.koppeltaal.util.RequestIdHolder;
import ca.uhn.fhir.jpa.starter.koppeltaal.util.ResourceOriginUtil;
import ca.uhn.fhir.jpa.subscription.model.CanonicalSubscription;
import ca.uhn.fhir.jpa.subscription.model.ResourceDeliveryMessage;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r4.model.AuditEvent;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.StringType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

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


  public AuditEventSubscriptionInterceptor(DaoRegistry daoRegistry, AuditEventService auditEventService, FhirContext fhirContext, RequestIdHolder requestIdHolder) {
    super(auditEventService, daoRegistry);
    this.fhirContext = fhirContext;
    this.requestIdHolder = requestIdHolder;
  }

  @Hook(value = Pointcut.SUBSCRIPTION_BEFORE_DELIVERY, order = Integer.MAX_VALUE)
  public void outgoingSubscription(CanonicalSubscription canonicalSubscription, ResourceDeliveryMessage message) {

    String traceId = message.getTransactionId();

    LOG.info("Starting AuditEvent creation for subscription with trade id [{}]", traceId);

    String requestId = UUID.randomUUID().toString(); //async, so always generate a new requestId

    Optional<String> correlationIdOptional = requestIdHolder.getRequestId(traceId);

    Resource resource = (Resource)message.getPayload(fhirContext);

    setTraceAndRequestIdHeaders(canonicalSubscription, traceId, requestId, correlationIdOptional);

    if (!(resource instanceof AuditEvent)) {
      AuditEventDto dto = new AuditEventDto();
      dto.setEventType(AuditEventDto.EventType.SendNotification);
      dto.setTraceId(traceId);
      dto.setRequestId(requestId);
      correlationIdOptional
        .ifPresent(dto::setCorrelationId);
      dto.setOutcome("0");
      dto.addResource(new Reference(resource));
      dto.addResource(new Reference(canonicalSubscription.getIdElement(fhirContext)));
      dto.setQuery(canonicalSubscription.getCriteriaString());
      dto.setDateTime(new Date());

      Optional<IIdType> resourceOriginDeviceId = ResourceOriginUtil.getResourceOriginDeviceId(resource);
      resourceOriginDeviceId.ifPresent(iIdType -> dto.setAgent(new Reference(iIdType)));

      LOG.info("Creating: {}", dto);

      auditEventService.submitAuditEvent(dto, null);
    } else {
      LOG.debug("Not creating AuditEvent as the resource is an instance of AuditEvent");
    }

  }

  private void setTraceAndRequestIdHeaders(CanonicalSubscription canonicalSubscription, String transactionId, String requestId, Optional<String> correlationIdOptional) {
    //There is no access to the response headers on the HttpServletResponse for subscriptions. Adding them in-memory to the subscription headers.
    //Cleaning up to make sure the previous in-memory results aren't sent as well
    canonicalSubscription.setHeaders(
      canonicalSubscription.getHeaders().stream()
        .filter(header -> !header.startsWith(TRACE_ID_HEADER_KEY) && !header.startsWith(HEADER_REQUEST_ID))
        .map(StringType::new)
        .collect(Collectors.toList())
    );

    canonicalSubscription.addHeader(TRACE_ID_HEADER_KEY + ": " + transactionId);
    canonicalSubscription.addHeader(HEADER_REQUEST_ID + ": " + requestId);
    correlationIdOptional.ifPresent(correlationId ->
      canonicalSubscription.addHeader(CORRELATION_HEADER_KEY + ": " + correlationId)
    );
  }


}
