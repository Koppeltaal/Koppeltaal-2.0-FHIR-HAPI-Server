package ca.uhn.fhir.jpa.starter.koppeltaal.interceptor;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.FhirVersionEnum;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.api.dao.IFhirSystemDao;
import ca.uhn.fhir.jpa.starter.koppeltaal.util.PermissionUtil;
import ca.uhn.fhir.jpa.starter.koppeltaal.util.ResourceOriginUtil;
import ca.uhn.fhir.jpa.subscription.model.CanonicalSubscription;
import ca.uhn.fhir.jpa.subscription.model.ResourceDeliveryMessage;
import ca.uhn.fhir.rest.api.EncodingEnum;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import org.hl7.fhir.r4.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SubscriptionNarrowingInterceptorTest {

  private SubscriptionNarrowingInterceptor interceptor;

  private FhirContext fhirContext = new FhirContext(FhirVersionEnum.R4);

  @BeforeEach
  void init(@Mock DaoRegistry daoRegistry) {
    IFhirSystemDao systemDaoMock = mock(IFhirSystemDao.class);

    when(daoRegistry.getSystemDao())
      .thenReturn(systemDaoMock);

    when(systemDaoMock.getContext())
      .thenReturn(fhirContext);

    IFhirResourceDao deviceDaoMock = mock(IFhirResourceDao.class);

    when(daoRegistry.getResourceDao(eq(Device.class)))
      .thenReturn(deviceDaoMock);

    Device subscriptionResourceOrigin = new Device();
    Identifier clientIdIdentifier = new Identifier();
    clientIdIdentifier.setSystem("http://vzvz.nl/fhir/NamingSystem/koppeltaal-client-id");
    clientIdIdentifier.setValue("default-client-id");
    subscriptionResourceOrigin.addIdentifier(clientIdIdentifier);

    when(deviceDaoMock.read(any(), any()))
      .thenReturn(subscriptionResourceOrigin);

    IFhirResourceDao subscriptionDaoMock = mock(IFhirResourceDao.class);

    when(daoRegistry.getResourceDao(eq(Subscription.class)))
      .thenReturn(subscriptionDaoMock);

    interceptor = new SubscriptionNarrowingInterceptor(daoRegistry);

    Subscription subscription = new Subscription();
    subscription.addExtension(new Extension(ResourceOriginUtil.RESOURCE_ORIGIN_SYSTEM, new Reference("Device/123")));

    when(subscriptionDaoMock.read(any(IdType.class), any(RequestDetails.class)))
      .thenReturn(subscription);
  }

  @Test
  public void shouldNotSendSubscription() {

    ResourceDeliveryMessage resourceDeliveryMessage = new ResourceDeliveryMessage();
    CanonicalSubscription canonicalSubscription = new CanonicalSubscription();
    canonicalSubscription.setIdElement(new IdType("sub-id"));

    Task thePayload = new Task();
    thePayload.addExtension(new Extension(ResourceOriginUtil.RESOURCE_ORIGIN_SYSTEM, new Reference("Device/456")));

    resourceDeliveryMessage.setPayload(fhirContext, thePayload, EncodingEnum.JSON);

    PermissionUtil.createOrUpdateScope("default-client-id", "system/Task.r?resource-origin=Device/789");

    assertFalse(
      interceptor.subscriptionBeforeDelivery(resourceDeliveryMessage, canonicalSubscription)
    );
  }

  @Test
  public void shouldSendSubscription() {
    ResourceDeliveryMessage resourceDeliveryMessage = new ResourceDeliveryMessage();
    CanonicalSubscription canonicalSubscription = new CanonicalSubscription();
    canonicalSubscription.setIdElement(new IdType("sub-id"));

    Task thePayload = new Task();
    thePayload.addExtension(new Extension(ResourceOriginUtil.RESOURCE_ORIGIN_SYSTEM, new Reference("Device/456")));

    resourceDeliveryMessage.setPayload(fhirContext, thePayload, EncodingEnum.JSON);

    PermissionUtil.createOrUpdateScope("default-client-id", "system/Task.r?resource-origin=Device/456");

    assertTrue(
      interceptor.subscriptionBeforeDelivery(resourceDeliveryMessage, canonicalSubscription)
    );
  }
}
