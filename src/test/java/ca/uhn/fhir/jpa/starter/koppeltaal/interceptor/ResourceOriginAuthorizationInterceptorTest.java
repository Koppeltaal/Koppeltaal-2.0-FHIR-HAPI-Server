package ca.uhn.fhir.jpa.starter.koppeltaal.interceptor;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.starter.koppeltaal.config.SmartBackendServiceConfiguration;
import ca.uhn.fhir.jpa.starter.koppeltaal.util.ResourceOriginUtil;
import ca.uhn.fhir.rest.api.RequestTypeEnum;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.exceptions.ForbiddenOperationException;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.ActivityDefinition;
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
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
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

  @Test
  public void shouldAllowHistoryAccessForDeletedResourceWithResourceOriginFromPreviousVersion() {
    // Test case: Successful history access for deleted resource by finding resource-origin from previous version
    final IdType resourceId = new IdType(ResourceType.ActivityDefinition.name(), "307e46d5-b1b8-4197-af3b-f7e149a42e2a", "2");

    // Create a proper history request using the test framework with authorization
    RequestDetails historyRequestDetails = getRequestDetailsAndConfigurePermission(
        RequestTypeEnum.GET,
        ResourceType.ActivityDefinition,
        resourceId,
        "rs", // read and search permissions
        "Device/test-device-123"
    );

    historyRequestDetails.setCompleteUrl("https://fhir-server.koppeltaal.headease.nl/fhir/DEFAULT/ActivityDefinition/307e46d5-b1b8-4197-af3b-f7e149a42e2a/_history");

    // Mock the deleted resource (current version without resource-origin)
    ActivityDefinition deletedResource = mock(ActivityDefinition.class);
    when(deletedResource.getIdElement()).thenReturn(resourceId);
    when(deletedResource.isDeleted()).thenReturn(true); // Mark as deleted

    // Mock the previous version with resource-origin
    IdType previousVersionId = new IdType(ResourceType.ActivityDefinition.name(), "307e46d5-b1b8-4197-af3b-f7e149a42e2a", "1");
    ActivityDefinition previousResource = new ActivityDefinition();
    previousResource.setId(previousVersionId);

    // Mock DAO registry to return the ActivityDefinition DAO
    @SuppressWarnings("unchecked")
    IFhirResourceDao<ActivityDefinition> activityDefinitionDao = (IFhirResourceDao<ActivityDefinition>) mock(IFhirResourceDao.class);
    when(daoRegistry.getResourceDao(ResourceType.ActivityDefinition.name())).thenReturn(activityDefinitionDao);

    // Mock the DAO to return deleted resource on first call, previous version on second call
    when(activityDefinitionDao.read(eq(resourceId), eq(historyRequestDetails), eq(true))).thenReturn(deletedResource);
    when(activityDefinitionDao.read(eq(previousVersionId), eq(historyRequestDetails), eq(true))).thenReturn(previousResource);

    // Mock resource origin utility - no origin for deleted resource, but has origin for previous version
    IdType expectedDeviceRef = new IdType("Device", "test-device-123");
    resourceOriginUtil.when(() -> ResourceOriginUtil.getResourceOriginDeviceId(eq(deletedResource)))
        .thenReturn(Optional.empty()); // Deleted resource has no resource-origin
    resourceOriginUtil.when(() -> ResourceOriginUtil.getResourceOriginDeviceId(eq(previousResource)))
        .thenReturn(Optional.of(expectedDeviceRef)); // Previous version has resource-origin

    // This should succeed - the interceptor should find the resource-origin from the previous version
    interceptor.authorizeRequest(historyRequestDetails);

    // If we reach here, the test passed (no exception thrown)
  }

  @Test
  public void shouldDenyHistoryAccessForDeletedResourceWhenNoPreviousVersionHasResourceOrigin() {
    // Test case: Failed history access when no previous version has resource-origin
    final IdType resourceId = new IdType(ResourceType.ActivityDefinition.name(), "no-origin-resource", "2");

    // Override the URL to be a history request - create a new mock for this specific test
    RequestDetails historyRequestDetails = mock(RequestDetails.class);
    when(historyRequestDetails.getResourceName()).thenReturn(ResourceType.ActivityDefinition.name());
    when(historyRequestDetails.getId()).thenReturn(resourceId);
    when(historyRequestDetails.getRequestType()).thenReturn(RequestTypeEnum.GET);
    when(historyRequestDetails.getCompleteUrl()).thenReturn("https://fhir-server.koppeltaal.headease.nl/fhir/DEFAULT/ActivityDefinition/no-origin-resource/_history");

    // Mock the deleted resource (current version without resource-origin)
    ActivityDefinition deletedResource = mock(ActivityDefinition.class);
    when(deletedResource.getIdElement()).thenReturn(resourceId);
    when(deletedResource.isDeleted()).thenReturn(true); // Mark as deleted

    // Mock the previous version also without resource-origin
    IdType previousVersionId = new IdType(ResourceType.ActivityDefinition.name(), "no-origin-resource", "1");
    ActivityDefinition previousResource = new ActivityDefinition();
    previousResource.setId(previousVersionId);

    // Mock DAO registry
    @SuppressWarnings("unchecked")
    IFhirResourceDao<ActivityDefinition> activityDefinitionDao = (IFhirResourceDao<ActivityDefinition>) mock(IFhirResourceDao.class);
    when(daoRegistry.getResourceDao(ResourceType.ActivityDefinition.name())).thenReturn(activityDefinitionDao);

    // Mock the DAO calls
    when(activityDefinitionDao.read(eq(resourceId), eq(historyRequestDetails), eq(true))).thenReturn(deletedResource);
    when(activityDefinitionDao.read(eq(previousVersionId), eq(historyRequestDetails), eq(true))).thenReturn(previousResource);

    // Mock resource origin utility - no origin for either version
    resourceOriginUtil.when(() -> ResourceOriginUtil.getResourceOriginDeviceId(eq(deletedResource)))
        .thenReturn(Optional.empty()); // Deleted resource has no resource-origin
    resourceOriginUtil.when(() -> ResourceOriginUtil.getResourceOriginDeviceId(eq(previousResource)))
        .thenReturn(Optional.empty()); // Previous version also has no resource-origin

    // This should fail - no resource-origin found in history
    assertThrows(ForbiddenOperationException.class, () ->
        interceptor.authorizeRequest(historyRequestDetails),
        "Should deny access when no version in history has resource-origin"
    );
  }

  @Test
  public void shouldDenyAccessForDeletedResourceWhenNotHistoryRequest() {
    // Test case: Normal GET request for deleted resource should still fail
    final IdType resourceId = new IdType(ResourceType.ActivityDefinition.name(), "deleted-resource", "2");

    RequestDetails requestDetails = getRequestDetailsAndConfigurePermission(
        RequestTypeEnum.GET,
        ResourceType.ActivityDefinition,
        resourceId,
        "rs", // read and search permissions
        "Device/test-device-123"
    );

    // This is NOT a history request - just a regular GET
    requestDetails.setCompleteUrl("https://fhir-server.koppeltaal.headease.nl/fhir/DEFAULT/ActivityDefinition/deleted-resource");

    // Mock the deleted resource
    ActivityDefinition deletedResource = mock(ActivityDefinition.class);
    when(deletedResource.getIdElement()).thenReturn(resourceId);
    when(deletedResource.isDeleted()).thenReturn(true); // Mark as deleted

    // Mock DAO registry
    @SuppressWarnings("unchecked")
    IFhirResourceDao<ActivityDefinition> activityDefinitionDao = (IFhirResourceDao<ActivityDefinition>) mock(IFhirResourceDao.class);
    when(daoRegistry.getResourceDao(ResourceType.ActivityDefinition.name())).thenReturn(activityDefinitionDao);

    when(activityDefinitionDao.read(eq(resourceId), eq(requestDetails), eq(true))).thenReturn(deletedResource);

    // Mock resource origin utility - no origin for deleted resource
    resourceOriginUtil.when(() -> ResourceOriginUtil.getResourceOriginDeviceId(eq(deletedResource)))
        .thenReturn(Optional.empty());

    // This should fail - regular requests for deleted resources without resource-origin should be denied
    assertThrows(ForbiddenOperationException.class, () ->
        interceptor.authorizeRequest(requestDetails),
        "Should deny regular GET access to deleted resource without resource-origin"
    );
  }

}
