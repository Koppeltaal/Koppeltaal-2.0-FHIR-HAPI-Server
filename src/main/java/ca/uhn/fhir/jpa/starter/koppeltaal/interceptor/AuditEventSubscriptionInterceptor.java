package ca.uhn.fhir.jpa.starter.koppeltaal.interceptor;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.starter.koppeltaal.dto.AuditEventDto;
import ca.uhn.fhir.jpa.starter.koppeltaal.service.AuditEventService;
import ca.uhn.fhir.jpa.subscription.model.CanonicalSubscription;
import ca.uhn.fhir.jpa.subscription.model.ResourceDeliveryMessage;
import org.apache.commons.lang3.RandomStringUtils;
import org.hl7.fhir.r4.model.AuditEvent;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.StringType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.stream.Collectors;

/**
 *
 */
@Component
@Interceptor
public class AuditEventSubscriptionInterceptor extends AbstactAuditEventInterceptor {
	private static final Logger LOG = LoggerFactory.getLogger(AuditEventSubscriptionInterceptor.class);
	protected final FhirContext fhirContext;

	public AuditEventSubscriptionInterceptor(DaoRegistry daoRegistry, AuditEventService auditEventService, FhirContext fhirContext) {
		super(auditEventService, daoRegistry);
		this.fhirContext = fhirContext;
	}

	@Hook(value = Pointcut.SUBSCRIPTION_BEFORE_DELIVERY, order = Integer.MAX_VALUE)
	public void outgoingSubscription(CanonicalSubscription canonicalSubscription, ResourceDeliveryMessage message) {
		String traceId = message.getTransactionId();

    String requestId = RandomStringUtils.randomAlphanumeric(16); //async, so always generate a new requestId

    //TODO: Ideally, the request-id provided by the request that triggered this subscription is passed down as a X-Correlation-Id header. However, the requestId seems inaccessible (see ca.uhn.fhir.rest.server.messaging.BaseResourceModifiedMessage.BaseResourceModifiedMessage(ca.uhn.fhir.context.FhirContext, org.hl7.fhir.instance.model.api.IBaseResource, ca.uhn.fhir.rest.server.messaging.BaseResourceMessage.OperationTypeEnum, ca.uhn.fhir.rest.api.server.RequestDetails)

    Resource resource = (Resource)message.getPayload(fhirContext);

    setTraceAndRequestIdHeaders(canonicalSubscription, traceId, requestId);

    if (!(resource instanceof AuditEvent)) {
			AuditEventDto dto = new AuditEventDto();
			dto.setEventType(AuditEventDto.EventType.SendNotification);
			dto.setTraceId(traceId);
			dto.setRequestId(requestId);
			dto.setOutcome("0");
			dto.addResource(resource);
			dto.setQuery(canonicalSubscription.getCriteriaString());
			dto.setDateTime(new Date());
			auditEventService.submitAuditEvent(dto, null);
		}

	}

  private void setTraceAndRequestIdHeaders(CanonicalSubscription canonicalSubscription, String transactionId, String requestId) {
    //There is no access to the response headers on the HttpServletResponse for subscriptions. Adding them in-memory to the subscription headers.
    //Cleaning up to make sure the previous in-memory results aren't sent as well
    canonicalSubscription.setHeaders(
      canonicalSubscription.getHeaders().stream()
        .filter(header -> !header.startsWith("X-Trace-Id: ") && !header.startsWith("X-Request-Id: "))
        .map(StringType::new)
        .collect(Collectors.toList())
    );

    canonicalSubscription.addHeader("X-Trace-Id: " + transactionId);
    canonicalSubscription.addHeader("X-Request-Id: " + requestId);
  }


}
