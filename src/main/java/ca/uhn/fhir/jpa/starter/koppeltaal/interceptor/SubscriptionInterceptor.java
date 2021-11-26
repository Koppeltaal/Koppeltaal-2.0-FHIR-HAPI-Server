package ca.uhn.fhir.jpa.starter.koppeltaal.interceptor;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.starter.koppeltaal.dto.CrudOperation;
import ca.uhn.fhir.jpa.starter.koppeltaal.dto.PermissionDto;
import ca.uhn.fhir.jpa.starter.koppeltaal.dto.PermissionScope;
import ca.uhn.fhir.jpa.starter.koppeltaal.service.SmartBackendServiceAuthorizationService;
import ca.uhn.fhir.jpa.starter.koppeltaal.util.ResourceOriginUtil;
import ca.uhn.fhir.jpa.subscription.model.CanonicalSubscription;
import ca.uhn.fhir.jpa.subscription.model.ResourceDeliveryMessage;
import java.util.List;
import java.util.Optional;
import org.apache.commons.lang3.StringUtils;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r4.model.Device;
import org.hl7.fhir.r4.model.DomainResource;
import org.hl7.fhir.r4.model.ResourceType;
import org.hl7.fhir.r4.model.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>Interceptor that handles "notification narrowing"</p>
 */
@Interceptor
public class SubscriptionInterceptor {

	private static final Logger LOG = LoggerFactory.getLogger(SubscriptionInterceptor.class);

	private final DaoRegistry daoRegistry;
	private final IFhirResourceDao<Device> deviceDao;
	private final IFhirResourceDao<Subscription> subscriptionDao;
	private final SmartBackendServiceAuthorizationService smartBackendServiceAuthorizationService;
	private final FhirContext context;

	public SubscriptionInterceptor(DaoRegistry daoRegistry, IFhirResourceDao<Device> deviceDao,
		SmartBackendServiceAuthorizationService smartBackendServiceAuthorizationService) {
		this.daoRegistry = daoRegistry;
		this.deviceDao = deviceDao;
		this.subscriptionDao = daoRegistry.getResourceDao(Subscription.class);
		this.context = daoRegistry.getSystemDao().getContext();
		this.smartBackendServiceAuthorizationService = smartBackendServiceAuthorizationService;
	}

	@Hook(Pointcut.SUBSCRIPTION_BEFORE_DELIVERY)
	public boolean subscriptionBeforeDelivery(ResourceDeliveryMessage message, CanonicalSubscription canonicalSubscription) {

		try {
			final IBaseResource payload = message.getPayload(context);

			final IIdType canonicalSubscriptionIdElement = canonicalSubscription.getIdElement(context);
			final Subscription subscription = subscriptionDao.read(canonicalSubscriptionIdElement);

			final Optional<IIdType> optionalSubscriptionDeviceId = ResourceOriginUtil.getResourceOriginDeviceId(
				subscription);

			if(!optionalSubscriptionDeviceId.isPresent()) {
				LOG.warn("No resource-origin found on canonicalSubscriptionIdElement [Subscription/{}]",
					canonicalSubscriptionIdElement.getIdPart());
				return true; //TODO: Decide whether we want this to break
			}

			final IIdType subscriptionDeviceId = optionalSubscriptionDeviceId.get();
			final List<PermissionDto> permissions = smartBackendServiceAuthorizationService.getPermissions(
				subscriptionDeviceId.getIdPart());

			if(payload instanceof DomainResource) {
				final Optional<IIdType> payloadOptionalDeviceId = ResourceOriginUtil.getResourceOriginDeviceId(
					payload);

				if(!payloadOptionalDeviceId.isPresent()) {
					return true; //TODO: Decide whether we want this to break
				}

				final IIdType payloadDeviceId = payloadOptionalDeviceId.get();
				boolean isResourceOwner = StringUtils.equals(subscriptionDeviceId.getIdPart(), payloadDeviceId.getIdPart());

				final ResourceType resourceType = ((DomainResource) payload).getResourceType();

				return permissions.stream()
					.anyMatch(permission ->
						permission.getResourceType() == resourceType &&
						permission.getOperation() == CrudOperation.READ &&
						(
								permission.getScope() == PermissionScope.ALL ||
								permission.getScope() == PermissionScope.OWN && isResourceOwner ||
								permission.getScope() == PermissionScope.GRANTED && permission.getGrantedDeviceIds().contains(payloadDeviceId.getIdPart())
						)
					);
			}
		} catch (Exception e) {
			LOG.error("Failed to execute Notification Narrowing. Non-breaking, might have notified for inaccessible resources!\n\nMessage: {}", message);
		}

		return true; //TODO: Decide whether we want this to break
	}

}
