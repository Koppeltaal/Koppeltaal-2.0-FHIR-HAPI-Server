package ca.uhn.fhir.jpa.starter.koppeltaal.interceptor;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.starter.koppeltaal.dto.AuditEventDto;
import ca.uhn.fhir.jpa.starter.koppeltaal.service.AuditEventService;
import ca.uhn.fhir.jpa.subscription.model.CanonicalSubscription;
import ca.uhn.fhir.jpa.subscription.model.ResourceDeliveryMessage;
import ca.uhn.fhir.jpa.subscription.model.ResourceModifiedMessage;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.AuditEvent;
import org.hl7.fhir.r4.model.Device;
import org.hl7.fhir.r4.model.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.UUID;

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
		String transactionId = message.getTransactionId();
		Resource resource = (Resource)message.getPayload(fhirContext);
		if (!(resource instanceof AuditEvent)) {
			AuditEventDto dto = new AuditEventDto();
			dto.setEventType(AuditEventDto.EventType.SendNotification);
			dto.setTraceId(transactionId);
			dto.setSpanId(message.getPayloadId());
			dto.setOutcome("0");
			dto.addResource(resource);
			dto.setQuery(canonicalSubscription.getCriteriaString());
			dto.setDateTime(new Date());
			auditEventService.submitAuditEvent(dto, null);
		}

	}


}
