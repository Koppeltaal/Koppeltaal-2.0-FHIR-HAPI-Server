package ca.uhn.fhir.jpa.starter.koppeltaal.interceptor;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.starter.koppeltaal.config.SmartBackendServiceConfiguration;
import ca.uhn.fhir.jpa.starter.koppeltaal.util.ResourceOriginUtil;
import ca.uhn.fhir.rest.api.RequestTypeEnum;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.exceptions.ForbiddenOperationException;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Device;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.ResourceType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;

@ExtendWith(MockitoExtension.class)
class ResourceOriginAuthorizationInterceptorTest extends BaseResourceOriginTest {

  private static MockedStatic<ResourceOriginUtil> resourceOriginUtil;

  private ResourceOriginAuthorizationInterceptor interceptor;

  private final IdType defaultDeviceRef = new IdType("Device", 123L);

  @BeforeAll
  public static void initAll() {
    resourceOriginUtil = mockStatic(ResourceOriginUtil.class);
  }

  @AfterAll
  public static void afterAll() {
    resourceOriginUtil.close();
  }

  @BeforeEach
  void init(@Mock DaoRegistry daoRegistry,
            @Mock IFhirResourceDao<Device> deviceDao,
            @Mock SmartBackendServiceConfiguration smartBackendServiceConfiguration
  ) {

    this.daoRegistry = daoRegistry;

    interceptor = new ResourceOriginAuthorizationInterceptor(
      daoRegistry,
      deviceDao,
      smartBackendServiceConfiguration
    );

    resourceOriginUtil.when(() -> ResourceOriginUtil.getResourceOriginDeviceId(any(IBaseResource.class)))
      .thenReturn(Optional.of(defaultDeviceRef));
  }

  @Test
  public void shouldCreate() {
    shouldAllow(RequestTypeEnum.POST, true);
  }

  @Test
  public void shouldNotCreate() {
    shouldNotAllow(RequestTypeEnum.POST, true);
  }

  @Test
  public void shouldReadOne() {
    shouldAllow(RequestTypeEnum.GET, true);
  }

  @Test
  public void shouldNotReadOne() {
    shouldNotAllow(RequestTypeEnum.GET, true);
  }

  @Test
  public void shouldReadAll() {
    shouldAllow(RequestTypeEnum.GET, false);
    shouldAllow(RequestTypeEnum.GET, false, "Device/456"); //should not fail as search narrowing handles this
  }

  @Test
  public void shouldNotReadAll() {
    shouldNotAllow(RequestTypeEnum.GET, false);
  }

  @Test
  public void shouldUpdate() {
    shouldAllow(RequestTypeEnum.PUT, true);
  }

  @Test
  public void shouldNotUpdate() {
    shouldNotAllow(RequestTypeEnum.PUT, true);
  }

  @Test
  public void shouldDelete() {
    shouldAllow(RequestTypeEnum.DELETE, true);
  }

  @Test
  public void shouldNotDelete() {
    shouldNotAllow(RequestTypeEnum.DELETE, true);
  }

  private void shouldAllow(RequestTypeEnum requestType, boolean hasResource, String... resourceOrigins) {
    final IdType resourceId = hasResource ? new IdType(ResourceType.Task.name(), 12L) : null;

    String crudsValue;
    switch (requestType) {
      case GET: crudsValue = "rs"; break;
      case POST: crudsValue = "c"; break;
      case PUT: crudsValue = "u"; break;
      case DELETE: crudsValue = "d"; break;
      default: throw new RuntimeException("invalid request type");
    }

    //without resource-origin, the ALL permission
    RequestDetails requestDetails = getRequestDetailsAndConfigurePermission(requestType, ResourceType.Task, resourceId, crudsValue);
    interceptor.authorizeRequest(requestDetails);

    //with OWN permission
    requestDetails = getRequestDetailsAndConfigurePermission(requestType, ResourceType.Task, resourceId, crudsValue, defaultDeviceRef.getValue());
    interceptor.authorizeRequest(requestDetails);

    //with GRANTED permission
    if(resourceOrigins != null) {
      requestDetails = getRequestDetailsAndConfigurePermission(requestType, ResourceType.Task, resourceId, crudsValue, resourceOrigins);
      interceptor.authorizeRequest(requestDetails);
    }
  }

  private void shouldNotAllow(RequestTypeEnum requestType, boolean hasResource, String... resourceOrigins) {
    final IdType resourceId = hasResource ? new IdType(ResourceType.Task.name(), 12L) : null;

    String crudsValue;
    switch (requestType) {
      case GET: crudsValue = "cud"; break;
      case POST: crudsValue = "ruds"; break;
      case PUT: crudsValue = "crds"; break;
      case DELETE: crudsValue = "crus"; break;
      default: throw new RuntimeException("invalid request type");
    }


    //without resource-origin, the ALL permission
    assertThrows(ForbiddenOperationException.class, () ->
      interceptor.authorizeRequest(
        getRequestDetailsAndConfigurePermission(requestType, ResourceType.Task, resourceId, crudsValue)
      )
    );

    //with OWN permission
    assertThrows(ForbiddenOperationException.class, () ->
      interceptor.authorizeRequest(
        getRequestDetailsAndConfigurePermission(requestType, ResourceType.Task, resourceId, crudsValue, defaultDeviceRef.getValue())
      )
    );

    //with GRANTED permission
    if(resourceOrigins != null) {
      assertThrows(ForbiddenOperationException.class, () ->
        interceptor.authorizeRequest(
          getRequestDetailsAndConfigurePermission(requestType, ResourceType.Task, resourceId, crudsValue, resourceOrigins)
       )
      );
    }
  }

  @Test
  public void shouldBeAbleToModifyOtherResourceWithPermissionInScope() {
    final IdType resourceId = new IdType(ResourceType.Task.name(), 12L);

    RequestDetails requestDetails = getRequestDetailsAndConfigurePermission(RequestTypeEnum.PUT, ResourceType.Task, resourceId, "cruds", "Device/456");

    final IdType otherDeviceId = new IdType("Device", 456L);
    resourceOriginUtil.when(() -> ResourceOriginUtil.getResourceOriginDeviceId(any(IBaseResource.class)))
      .thenReturn(Optional.of(otherDeviceId));

    interceptor.authorizeRequest(requestDetails);
  }

  @Test
  public void shouldFailWhenDeviceIdHasSameStartButIsDifferent() {
    final IdType resourceId = new IdType(ResourceType.Task.name(), 12L);

    RequestDetails requestDetails = getRequestDetailsAndConfigurePermission(RequestTypeEnum.PUT, ResourceType.Task, resourceId, "cruds", "Device/4567");

    final IdType otherDeviceId = new IdType("Device", 456L);
    resourceOriginUtil.when(() -> ResourceOriginUtil.getResourceOriginDeviceId(any(IBaseResource.class)))
      .thenReturn(Optional.of(otherDeviceId));

    assertThrows(ForbiddenOperationException.class, () ->
      interceptor.authorizeRequest(requestDetails)
    );
  }

  @Test
  public void shouldBeAbleToModifySearchParameterWithoutResourceOrigin() {
    final IdType resourceId = new IdType(ResourceType.SearchParameter.name(), 14L);

    RequestDetails requestDetails = getRequestDetailsAndConfigurePermission(RequestTypeEnum.PUT, ResourceType.SearchParameter, resourceId, "cruds");

    resourceOriginUtil.when(() -> ResourceOriginUtil.getResourceOriginDeviceId(any(IBaseResource.class)))
      .thenReturn(Optional.empty());

    interceptor.authorizeRequest(requestDetails);
  }

  @Test
  public void shouldNotBeAbleToModifyActivityDefinitionWithoutResourceOrigin() {
    final IdType resourceId = new IdType(ResourceType.ActivityDefinition.name(), 14L);

    RequestDetails requestDetails = getRequestDetailsAndConfigurePermission(RequestTypeEnum.PUT, ResourceType.ActivityDefinition, resourceId, "cruds");

    resourceOriginUtil.when(() -> ResourceOriginUtil.getResourceOriginDeviceId(any(IBaseResource.class)))
      .thenReturn(Optional.empty());

    assertThrows(ForbiddenOperationException.class, () ->
      interceptor.authorizeRequest(requestDetails)
    );
  }

}
