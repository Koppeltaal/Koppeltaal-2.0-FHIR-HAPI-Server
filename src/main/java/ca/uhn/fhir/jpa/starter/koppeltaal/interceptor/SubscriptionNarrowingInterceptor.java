package ca.uhn.fhir.jpa.starter.koppeltaal.interceptor;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.partition.SystemRequestDetails;
import ca.uhn.fhir.jpa.starter.koppeltaal.dto.CrudOperation;
import ca.uhn.fhir.jpa.starter.koppeltaal.util.PermissionUtil;
import ca.uhn.fhir.jpa.starter.koppeltaal.util.ResourceOriginUtil;
import ca.uhn.fhir.jpa.subscription.model.CanonicalSubscription;
import ca.uhn.fhir.jpa.subscription.model.ResourceDeliveryMessage;
import ca.uhn.fhir.rest.server.exceptions.ForbiddenOperationException;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r4.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * <p>Interceptor that handles "notification narrowing"</p>
 */
@Interceptor
public class SubscriptionNarrowingInterceptor {

	private static final Logger LOG = LoggerFactory.getLogger(SubscriptionNarrowingInterceptor.class);

	private final IFhirResourceDao<Subscription> subscriptionDao;
	private final IFhirResourceDao<Device> deviceDao;
	private final FhirContext context;

	public SubscriptionNarrowingInterceptor(DaoRegistry daoRegistry) {
		this.subscriptionDao = daoRegistry.getResourceDao(Subscription.class);
		this.deviceDao = daoRegistry.getResourceDao(Device.class);
		this.context = daoRegistry.getSystemDao().getContext();
	}

	@Hook(Pointcut.SUBSCRIPTION_BEFORE_DELIVERY)
	public boolean subscriptionBeforeDelivery(ResourceDeliveryMessage message, CanonicalSubscription canonicalSubscription) {

		try {
			final IBaseResource payload = message.getPayload(context);

			final IIdType canonicalSubscriptionIdElement = canonicalSubscription.getIdElement(context);
			final Subscription subscription = subscriptionDao.read(canonicalSubscriptionIdElement, new SystemRequestDetails());

			final Optional<IIdType> optionalSubscriptionDeviceId = ResourceOriginUtil.getResourceOriginDeviceId(subscription);

			if(optionalSubscriptionDeviceId.isEmpty()) {
				LOG.warn("No resource-origin found on canonicalSubscriptionIdElement [Subscription/{}]. Still sending notification.",
					canonicalSubscriptionIdElement.getIdPart());
				return true; //TODO: Decide whether we want this to break
			}

			final IIdType subscriptionDeviceId = optionalSubscriptionDeviceId.get();
      Device subscriptionOriginDevice = deviceDao.read(subscriptionDeviceId, new SystemRequestDetails());

      Identifier clientId = subscriptionOriginDevice.getIdentifier().stream()
        .filter((identifier -> "http://vzvz.nl/fhir/NamingSystem/koppeltaal-client-id".equals(identifier.getSystem())))
        .findAny()
        .orElseThrow(() -> new ForbiddenOperationException("Cannot determine client id based on device id " + subscriptionDeviceId.getValue()));

      Optional<String> scope = PermissionUtil.getScope(clientId.getValue());

      if(scope.isEmpty()) {
        LOG.info("Scope for client_id [{}] not found in cache yet. Still sending notification.", clientId.getValue());
        return true; //cache not yet updated with permissions, simply send the notification
      }

			if(payload instanceof DomainResource) {
				final Optional<IIdType> payloadOptionalDeviceId = ResourceOriginUtil.getResourceOriginDeviceId(payload);

				if(payloadOptionalDeviceId.isEmpty()) {
        LOG.info("No Device found for [{}] (payload inside ResourceDeliveryMessage.mesage). Still sending notification.", payload.getIdElement().getValue());
					return true; //TODO: Decide whether we want this to break
				}

				final IIdType payloadDeviceId = payloadOptionalDeviceId.get();

				final ResourceType resourceType = ((DomainResource) payload).getResourceType();

        LOG.info("Device [{}] found for [{}], determine if it has permission next", payloadDeviceId.getIdPart(), payload.getIdElement().getValue());
        return PermissionUtil.hasPermission(CrudOperation.READ, resourceType, payloadDeviceId.getIdPart(), scope.get());
			}
		} catch (Exception e) {
			LOG.error("Failed to execute Notification Narrowing. Non-breaking, might have notified for inaccessible resources! Still sending notification.\n\nMessage: {}", message);
		}

    LOG.warn("Final fallthrough code reached. Still sending notification.");
		return true; //TODO: Decide whether we want this to break
	}

}
