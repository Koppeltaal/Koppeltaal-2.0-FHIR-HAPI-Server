package ca.uhn.fhir.jpa.starter.koppeltaal.interceptor;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.starter.koppeltaal.config.SmartBackendServiceConfiguration;
import ca.uhn.fhir.jpa.starter.koppeltaal.util.PermissionUtil;
import ca.uhn.fhir.jpa.starter.koppeltaal.util.ResourceOriginUtil;
import ca.uhn.fhir.rest.api.Constants;
import ca.uhn.fhir.rest.api.RequestTypeEnum;
import ca.uhn.fhir.rest.api.RestOperationTypeEnum;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.exceptions.BaseServerResponseException;
import ca.uhn.fhir.rest.server.exceptions.ForbiddenOperationException;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import ca.uhn.fhir.rest.server.exceptions.ResourceGoneException;
import ca.uhn.fhir.rest.server.exceptions.ResourceVersionConflictException;
import ca.uhn.fhir.rest.server.servlet.ServletRequestDetails;
import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
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
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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

  private static final String DOMAIN_ADMIN_CLIENT_ID = "domain-admin";

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

    when(smartBackendServiceConfiguration.getDomainAdminClientId()).thenReturn(DOMAIN_ADMIN_CLIENT_ID);
    requestedBy("some-client");
  }

  private static void requestedBy(String clientId) {
    resourceOriginUtil.when(() -> ResourceOriginUtil.getRequesterClientId(any(RequestDetails.class)))
      .thenReturn(Optional.of(clientId));
  }

  private static RequestDetails withHeader(RequestDetails requestDetails, String name, String value) {
    ((MockHttpServletRequest) ((ServletRequestDetails) requestDetails).getServletRequest()).addHeader(name, value);
    return requestDetails;
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

    historyRequestDetails.setRequestPath("ActivityDefinition/307e46d5-b1b8-4197-af3b-f7e149a42e2a/_history");
    historyRequestDetails.setRestOperationType(RestOperationTypeEnum.HISTORY_INSTANCE);

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

    // A valid token with read permission, so the denial can only come from the resource-origin resolution
    RequestDetails historyRequestDetails = getRequestDetailsAndConfigurePermission(
        RequestTypeEnum.GET,
        ResourceType.ActivityDefinition,
        resourceId,
        "rs",
        "Device/test-device-123"
    );
    historyRequestDetails.setRequestPath("ActivityDefinition/no-origin-resource/_history");
    historyRequestDetails.setRestOperationType(RestOperationTypeEnum.HISTORY_INSTANCE);

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

    // ... after actually looking at the previous version
    verify(activityDefinitionDao).read(eq(previousVersionId), eq(historyRequestDetails), eq(true));
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

    when(activityDefinitionDao.read(eq(resourceId), eq(requestDetails), eq(false))).thenReturn(deletedResource);

    // Mock resource origin utility - no origin for deleted resource
    resourceOriginUtil.when(() -> ResourceOriginUtil.getResourceOriginDeviceId(eq(deletedResource)))
        .thenReturn(Optional.empty());

    // This should fail - regular requests for deleted resources without resource-origin should be denied
    assertThrows(ForbiddenOperationException.class, () ->
        interceptor.authorizeRequest(requestDetails),
        "Should deny regular GET access to deleted resource without resource-origin"
    );
  }

  @Test
  public void shouldReturn410GoneForDeletedResourceOnNonHistoryRequest() {
    // Test case: Regular GET request to deleted resource should return 410 Gone
    final IdType resourceId = new IdType(ResourceType.ActivityDefinition.name(), "deleted-resource", "2");

    RequestDetails requestDetails = getRequestDetailsAndConfigurePermission(
        RequestTypeEnum.GET,
        ResourceType.ActivityDefinition,
        resourceId,
        "rs", // read and search permissions
        "Device/test-device-123"
    );

    // This is NOT a history request - just a regular GET (no /_history in path)
    requestDetails.setRequestPath("ActivityDefinition/deleted-resource");

    // Mock DAO registry
    @SuppressWarnings("unchecked")
    IFhirResourceDao<ActivityDefinition> activityDefinitionDao = (IFhirResourceDao<ActivityDefinition>) mock(IFhirResourceDao.class);
    when(daoRegistry.getResourceDao(ResourceType.ActivityDefinition.name())).thenReturn(activityDefinitionDao);

    // Mock the DAO to throw ResourceGoneException when trying to read the deleted resource (allowDeleted=false)
    when(activityDefinitionDao.read(eq(resourceId), eq(requestDetails), eq(false)))
        .thenThrow(new ResourceGoneException("Resource has been deleted"));

    // This should throw ResourceGoneException (which translates to HTTP 410 Gone)
    assertThrows(ResourceGoneException.class, () ->
        interceptor.authorizeRequest(requestDetails),
        "Should return 410 Gone for regular GET request to deleted resource"
    );
  }

  @Test
  public void shouldAllowCapabilitiesWithoutToken() {
    ServletRequestDetails requestDetails = new ServletRequestDetails();
    requestDetails.setRequestType(RequestTypeEnum.GET);
    requestDetails.setRestOperationType(RestOperationTypeEnum.METADATA);
    requestDetails.setServletRequest(new MockHttpServletRequest());

    interceptor.authorizeRequest(requestDetails);
  }

  @Test
  public void shouldAllowPagingOfAnEarlierSearch() {
    // the search itself was authorized and narrowed, the response filter handles the returned page
    interceptor.authorizeRequest(getSystemRequestDetails(RestOperationTypeEnum.GET_PAGE, RequestTypeEnum.GET, null));
  }

  @Test
  public void shouldDenyRequestWithoutOperationType() {
    RequestDetails requestDetails = getRequestDetailsAndConfigurePermission(RequestTypeEnum.GET, ResourceType.Task, new IdType(ResourceType.Task.name(), 12L), "rs");
    requestDetails.setRestOperationType(null);

    assertThrows(ForbiddenOperationException.class, () -> interceptor.authorizeRequest(requestDetails));
  }

  @Test
  public void shouldDenySystemLevelHistory() {
    assertThrows(ForbiddenOperationException.class, () ->
      interceptor.authorizeRequest(getSystemRequestDetails(RestOperationTypeEnum.HISTORY_SYSTEM, RequestTypeEnum.GET, null))
    );
  }

  @Test
  public void shouldDenySystemLevelSearch() {
    assertThrows(ForbiddenOperationException.class, () ->
      interceptor.authorizeRequest(getSystemRequestDetails(RestOperationTypeEnum.SEARCH_SYSTEM, RequestTypeEnum.GET, null))
    );
  }

  @Test
  public void shouldDenyTransactionAndBatchBundles() {
    for (RestOperationTypeEnum operationType : List.of(RestOperationTypeEnum.TRANSACTION, RestOperationTypeEnum.BATCH)) {
      assertThrows(ForbiddenOperationException.class, () ->
        interceptor.authorizeRequest(getSystemRequestDetails(operationType, RequestTypeEnum.POST, null)),
        "Should deny " + operationType
      );
    }
  }

  @Test
  public void shouldDenySystemLevelOperations() {
    for (String operation : List.of("$expunge", "$diff", "$reindex", "$meta", "$get-resource-counts", "$validate")) {
      assertThrows(ForbiddenOperationException.class, () ->
        interceptor.authorizeRequest(getSystemRequestDetails(RestOperationTypeEnum.EXTENDED_OPERATION_SERVER, RequestTypeEnum.POST, operation)),
        "Should deny " + operation
      );
    }
  }

  @Test
  public void shouldDenyInstanceLevelOperations() {
    // instance level $validate with mode=delete reveals the resources referencing the instance
    for (String operation : List.of("$validate", "$expunge", "$meta-delete", "$meta-add", "$everything", "$diff")) {
      RequestDetails requestDetails = getRequestDetailsAndConfigurePermission(RequestTypeEnum.POST, ResourceType.Patient, new IdType(ResourceType.Patient.name(), 12L), "cruds");
      requestDetails.setRestOperationType(RestOperationTypeEnum.EXTENDED_OPERATION_INSTANCE);
      requestDetails.setOperation(operation);

      assertThrows(ForbiddenOperationException.class, () -> interceptor.authorizeRequest(requestDetails), "Should deny " + operation);
    }
  }

  @Test
  public void shouldDenyTypeLevelOperationsOtherThanValidate() {
    for (String operation : List.of("$everything", "$expunge", "$meta")) {
      RequestDetails requestDetails = getRequestDetailsAndConfigurePermission(RequestTypeEnum.GET, ResourceType.Patient, null, "cruds");
      requestDetails.setRestOperationType(RestOperationTypeEnum.EXTENDED_OPERATION_TYPE);
      requestDetails.setOperation(operation);

      assertThrows(ForbiddenOperationException.class, () -> interceptor.authorizeRequest(requestDetails), "Should deny " + operation);
    }
  }

  @Test
  public void shouldAllowTypeLevelValidate() {
    RequestDetails requestDetails = getRequestDetailsAndConfigurePermission(RequestTypeEnum.POST, ResourceType.Task, null, "c", defaultDeviceRef.getValue());
    requestDetails.setRestOperationType(RestOperationTypeEnum.EXTENDED_OPERATION_TYPE);
    requestDetails.setOperation("$validate");

    interceptor.authorizeRequest(requestDetails);
  }

  @Test
  public void shouldNotAllowInstanceExpungeForDomainAdmin() {
    // the definitive deletion is an internal server step (TOP-KT-028), $expunge is disabled in the configuration
    requestedBy(DOMAIN_ADMIN_CLIENT_ID);

    assertThrows(ForbiddenOperationException.class, () ->
      interceptor.authorizeRequest(getExpungeRequestDetails(RestOperationTypeEnum.EXTENDED_OPERATION_INSTANCE, "cruds"))
    );
  }

  @Test
  public void shouldNotAllowInstanceExpungeForOtherClients() {
    requestedBy("some-client");

    assertThrows(ForbiddenOperationException.class, () ->
      interceptor.authorizeRequest(getExpungeRequestDetails(RestOperationTypeEnum.EXTENDED_OPERATION_INSTANCE, "cruds"))
    );
  }

  @Test
  public void shouldNotAllowTypeOrSystemLevelExpungeForDomainAdmin() {
    requestedBy(DOMAIN_ADMIN_CLIENT_ID);

    assertThrows(ForbiddenOperationException.class, () ->
      interceptor.authorizeRequest(getExpungeRequestDetails(RestOperationTypeEnum.EXTENDED_OPERATION_TYPE, "cruds"))
    );
    assertThrows(ForbiddenOperationException.class, () ->
      interceptor.authorizeRequest(getSystemRequestDetails(RestOperationTypeEnum.EXTENDED_OPERATION_SERVER, RequestTypeEnum.POST, "$expunge"))
    );
  }

  private RequestDetails getExpungeRequestDetails(RestOperationTypeEnum operationType, String crudsValue, String... resourceOrigins) {
    final IdType resourceId = operationType == RestOperationTypeEnum.EXTENDED_OPERATION_INSTANCE ? new IdType(ResourceType.Patient.name(), 12L) : null;

    RequestDetails requestDetails = getRequestDetailsAndConfigurePermission(RequestTypeEnum.POST, ResourceType.Patient, resourceId, crudsValue, resourceOrigins);
    requestDetails.setRestOperationType(operationType);
    requestDetails.setOperation("$expunge");
    return requestDetails;
  }

  @Test
  public void shouldRejectConditionalCreate() {
    RequestDetails requestDetails = getRequestDetailsAndConfigurePermission(RequestTypeEnum.POST, ResourceType.Patient, null, "c", defaultDeviceRef.getValue());
    withHeader(requestDetails, Constants.HEADER_IF_NONE_EXIST, "identifier=http://example.org|123");

    assertThrows(InvalidRequestException.class, () -> interceptor.authorizeRequest(requestDetails));
  }

  @Test
  public void shouldDenyPatchMetaTagAndOtherUnsupportedInteractions() {
    for (RestOperationTypeEnum operationType : List.of(
      RestOperationTypeEnum.PATCH,
      RestOperationTypeEnum.META,
      RestOperationTypeEnum.META_ADD,
      RestOperationTypeEnum.META_DELETE,
      RestOperationTypeEnum.ADD_TAGS,
      RestOperationTypeEnum.DELETE_TAGS,
      RestOperationTypeEnum.GET_TAGS,
      RestOperationTypeEnum.GRAPHQL_REQUEST,
      RestOperationTypeEnum.UPDATE_REWRITE_HISTORY,
      RestOperationTypeEnum.VALIDATE)) {

      RequestDetails requestDetails = getRequestDetailsAndConfigurePermission(RequestTypeEnum.PUT, ResourceType.Task, new IdType(ResourceType.Task.name(), 12L), "cruds");
      requestDetails.setRestOperationType(operationType);

      assertThrows(ForbiddenOperationException.class, () -> interceptor.authorizeRequest(requestDetails), "Should deny " + operationType);
    }
  }

  @Test
  public void shouldTreatPostSearchAsRead() {
    RequestDetails requestDetails = getRequestDetailsAndConfigurePermission(RequestTypeEnum.POST, ResourceType.Task, null, "rs", defaultDeviceRef.getValue());
    requestDetails.setRestOperationType(RestOperationTypeEnum.SEARCH_TYPE);
    interceptor.authorizeRequest(requestDetails);

    RequestDetails createOnlyRequestDetails = getRequestDetailsAndConfigurePermission(RequestTypeEnum.POST, ResourceType.Task, null, "c", defaultDeviceRef.getValue());
    createOnlyRequestDetails.setRestOperationType(RestOperationTypeEnum.SEARCH_TYPE);
    assertThrows(ForbiddenOperationException.class, () -> interceptor.authorizeRequest(createOnlyRequestDetails));
  }

  @Test
  public void shouldRejectUnsupportedIncludeParameters() {
    // HAPI also reads a "." as qualifier separator
    for (String parameter : List.of("_revinclude", "_revinclude:iterate", "_revinclude.x", "_contained", "_containedType")) {
      RequestDetails requestDetails = getRequestDetailsAndConfigurePermission(RequestTypeEnum.GET, ResourceType.Patient, null, "rs");
      requestDetails.setParameters(Map.of(parameter, new String[]{"*"}));

      assertThrows(InvalidRequestException.class, () -> interceptor.authorizeRequest(requestDetails), "Should reject " + parameter);
    }
  }

  @Test
  public void shouldAllowIncludeParameters() {
    // included resources of other resource-origins are removed by the ResourceOriginResponseFilterInterceptor
    for (String parameter : List.of("_include", "_include:iterate")) {
      RequestDetails requestDetails = getRequestDetailsAndConfigurePermission(RequestTypeEnum.GET, ResourceType.Task, null, "rs", defaultDeviceRef.getValue());
      requestDetails.setParameters(Map.of(parameter, new String[]{"Task:instantiates"}));

      interceptor.authorizeRequest(requestDetails);
    }
  }

  @Test
  public void shouldAllowMaintenanceOperationsForDomainAdmin() {
    requestedBy(DOMAIN_ADMIN_CLIENT_ID);

    for (String operation : List.of("$reindex", "$trigger-subscription")) {
      interceptor.authorizeRequest(getSystemRequestDetails(RestOperationTypeEnum.EXTENDED_OPERATION_SERVER, RequestTypeEnum.POST, operation));
    }

    RequestDetails instanceReindex = getRequestDetailsAndConfigurePermission(RequestTypeEnum.POST, ResourceType.Patient, new IdType(ResourceType.Patient.name(), 12L), "r");
    instanceReindex.setRestOperationType(RestOperationTypeEnum.EXTENDED_OPERATION_INSTANCE);
    instanceReindex.setOperation("$reindex");
    interceptor.authorizeRequest(instanceReindex);

    RequestDetails triggerSubscription = getRequestDetailsAndConfigurePermission(RequestTypeEnum.POST, ResourceType.Subscription, new IdType(ResourceType.Subscription.name(), 3L), "r");
    triggerSubscription.setRestOperationType(RestOperationTypeEnum.EXTENDED_OPERATION_INSTANCE);
    triggerSubscription.setOperation("$trigger-subscription");
    interceptor.authorizeRequest(triggerSubscription);
  }

  @Test
  public void shouldNotAllowMaintenanceOperationsForOtherClients() {
    requestedBy("some-client");

    for (String operation : List.of("$reindex", "$trigger-subscription")) {
      assertThrows(ForbiddenOperationException.class, () ->
        interceptor.authorizeRequest(getSystemRequestDetails(RestOperationTypeEnum.EXTENDED_OPERATION_SERVER, RequestTypeEnum.POST, operation)),
        "Should deny " + operation
      );
    }
  }

  @Test
  public void shouldAllowTerminologyOperationsWithUnrestrictedReadPermission() {
    // an expansion or lookup resolves any ValueSet or CodeSystem by its canonical url, including dependencies
    for (ResourceType resourceType : List.of(ResourceType.ValueSet, ResourceType.CodeSystem)) {
      for (String operation : List.of("$expand", "$lookup", "$validate-code")) {
        for (String scope : List.of("system/" + resourceType.name() + ".rs", "system/*.r")) {
          interceptor.authorizeRequest(getTerminologyRequestDetails(resourceType, operation, null, scope));
        }
      }
    }
  }

  @Test
  public void shouldNotAllowTerminologyOperationsWithRestrictedOrNoReadPermission() {
    for (String scope : List.of("system/ValueSet.rs?resource-origin=Device/123", "system/Task.rs", "system/ValueSet.c")) {
      assertThrows(ForbiddenOperationException.class, () ->
        interceptor.authorizeRequest(getTerminologyRequestDetails(ResourceType.ValueSet, "$expand", null, scope)),
        "Should deny with " + scope
      );
      assertThrows(ForbiddenOperationException.class, () ->
        interceptor.authorizeRequest(getTerminologyRequestDetails(ResourceType.ValueSet, "$expand", new IdType(ResourceType.ValueSet.name(), 14L), scope)),
        "Should deny instance level with " + scope
      );
    }
  }

  @Test
  public void shouldAllowTerminologyOperationsForDomainAdmin() {
    requestedBy(DOMAIN_ADMIN_CLIENT_ID);

    interceptor.authorizeRequest(getTerminologyRequestDetails(ResourceType.CodeSystem, "$lookup", null, "system/Task.rs"));
    interceptor.authorizeRequest(getTerminologyRequestDetails(ResourceType.ValueSet, "$expand", new IdType(ResourceType.ValueSet.name(), 14L), "system/Task.rs"));
  }

  private RequestDetails getTerminologyRequestDetails(ResourceType resourceType, String operation, IdType resourceId, String scope) {
    RequestDetails requestDetails = getRequestDetailsAndConfigurePermission(RequestTypeEnum.GET, resourceType, resourceId, "rs");
    setScope(requestDetails, scope);
    requestDetails.setRestOperationType(resourceId == null
      ? RestOperationTypeEnum.EXTENDED_OPERATION_TYPE
      : RestOperationTypeEnum.EXTENDED_OPERATION_INSTANCE);
    requestDetails.setOperation(operation);
    return requestDetails;
  }

  @Test
  public void shouldNotAllowTerminologyOperationsOnOtherResourceTypes() {
    RequestDetails requestDetails = getRequestDetailsAndConfigurePermission(RequestTypeEnum.GET, ResourceType.Patient, null, "rs");
    requestDetails.setRestOperationType(RestOperationTypeEnum.EXTENDED_OPERATION_TYPE);
    requestDetails.setOperation("$expand");

    assertThrows(ForbiddenOperationException.class, () -> interceptor.authorizeRequest(requestDetails));
  }

  @Test
  public void shouldHideTheReferencingResourceOfADeleteConflict() {
    RequestDetails requestDetails = getRequestDetailsAndConfigurePermission(RequestTypeEnum.DELETE, ResourceType.Patient, new IdType(ResourceType.Patient.name(), 12L), "d", defaultDeviceRef.getValue());

    BaseServerResponseException sanitized = interceptor.hideDeleteConflictDetails(requestDetails, new ResourceVersionConflictException(
      "Unable to delete Patient/12 because at least one resource has a reference to this resource. First reference found was resource Task/99 in path Task.for"));

    assertEquals(409, sanitized.getStatusCode());
    assertFalse(sanitized.getMessage().contains("Task/99"), sanitized.getMessage());
  }

  @Test
  public void shouldKeepDeleteConflictDetailsForUnrestrictedDeletePermission() {
    RequestDetails requestDetails = getRequestDetailsAndConfigurePermission(RequestTypeEnum.DELETE, ResourceType.Patient, new IdType(ResourceType.Patient.name(), 12L), "d");
    setScope(requestDetails, "system/*.cruds");

    assertNull(interceptor.hideDeleteConflictDetails(requestDetails, new ResourceVersionConflictException("Unable to delete Patient/12 ...")));
  }

  @Test
  public void shouldNotTouchOtherExceptions() {
    RequestDetails readRequestDetails = getRequestDetailsAndConfigurePermission(RequestTypeEnum.GET, ResourceType.Patient, new IdType(ResourceType.Patient.name(), 12L), "rs", defaultDeviceRef.getValue());
    assertNull(interceptor.hideDeleteConflictDetails(readRequestDetails, new ResourceVersionConflictException("conflict")));

    RequestDetails deleteRequestDetails = getRequestDetailsAndConfigurePermission(RequestTypeEnum.DELETE, ResourceType.Patient, new IdType(ResourceType.Patient.name(), 12L), "d", defaultDeviceRef.getValue());
    assertNull(interceptor.hideDeleteConflictDetails(deleteRequestDetails, new ForbiddenOperationException("Unauthorized")));
  }

  @Test
  public void shouldRefreshTheScopeCacheForEveryRequestWithAToken() {
    requestedBy("cache-client");

    // also when the interaction is denied
    assertThrows(ForbiddenOperationException.class, () ->
      interceptor.authorizeRequest(getSystemRequestDetails(RestOperationTypeEnum.TRANSACTION, RequestTypeEnum.POST, null))
    );

    assertEquals(Optional.of("system/*.cruds"), PermissionUtil.getScope("cache-client"));
  }

  @Test
  public void shouldNotRefreshTheScopeCacheFromATokenThatWasNotValidated() {
    requestedBy("victim-client");
    PermissionUtil.createOrUpdateScope("victim-client", "system/Task.r?resource-origin=Device/1");

    // the JwtSecurityInterceptor does not require a valid token on /metadata
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("Authorization", "Bearer " + JWT.create()
      .withClaim("azp", "victim-client")
      .withClaim("scope", "system/*.r")
      .sign(Algorithm.HMAC256("forged")));
    ServletRequestDetails requestDetails = new ServletRequestDetails();
    requestDetails.setRequestType(RequestTypeEnum.GET);
    requestDetails.setRestOperationType(RestOperationTypeEnum.METADATA);
    requestDetails.setServletRequest(request);

    interceptor.authorizeRequest(requestDetails);

    assertEquals(Optional.of("system/Task.r?resource-origin=Device/1"), PermissionUtil.getScope("victim-client"));
  }

  @Test
  public void shouldAllowTypeLevelHistoryWithReadPermission() {
    // the response filter removes entries of other resource-origins
    RequestDetails requestDetails = getRequestDetailsAndConfigurePermission(RequestTypeEnum.GET, ResourceType.Task, null, "rs", defaultDeviceRef.getValue());
    requestDetails.setRestOperationType(RestOperationTypeEnum.HISTORY_TYPE);

    interceptor.authorizeRequest(requestDetails);
  }

  @Test
  public void shouldNotAllowTypeLevelHistoryWithoutReadPermission() {
    RequestDetails requestDetails = getRequestDetailsAndConfigurePermission(RequestTypeEnum.GET, ResourceType.Task, null, "cud", defaultDeviceRef.getValue());
    requestDetails.setRestOperationType(RestOperationTypeEnum.HISTORY_TYPE);

    assertThrows(ForbiddenOperationException.class, () -> interceptor.authorizeRequest(requestDetails));
  }

  @Test
  public void shouldOnlyAllowConditionalUpdateAndDeleteWithUnrestrictedScope() {
    for (RequestTypeEnum requestType : List.of(RequestTypeEnum.PUT, RequestTypeEnum.DELETE)) {
      String crudsValue = requestType == RequestTypeEnum.PUT ? "u" : "d";

      interceptor.authorizeRequest(getRequestDetailsAndConfigurePermission(requestType, ResourceType.Task, null, crudsValue));

      assertThrows(ForbiddenOperationException.class, () ->
        interceptor.authorizeRequest(getRequestDetailsAndConfigurePermission(requestType, ResourceType.Task, null, crudsValue, defaultDeviceRef.getValue()))
      );
    }
  }

  @Test
  public void shouldAllowSearchingConformanceResourcesWithoutScope() {
    RequestDetails requestDetails = getRequestDetailsAndConfigurePermission(RequestTypeEnum.GET, ResourceType.ValueSet, null, "rs");
    setScope(requestDetails, "system/Task.rs?resource-origin=Device/123");

    interceptor.authorizeRequest(requestDetails);
  }

  @Test
  public void shouldAllowReadingConformanceResourceWithoutResourceOrigin() {
    RequestDetails requestDetails = getRequestDetailsAndConfigurePermission(RequestTypeEnum.GET, ResourceType.ValueSet, new IdType(ResourceType.ValueSet.name(), 14L), "rs");
    setScope(requestDetails, "system/Task.rs?resource-origin=Device/123");

    resourceOriginUtil.when(() -> ResourceOriginUtil.getResourceOriginDeviceId(any(IBaseResource.class)))
      .thenReturn(Optional.empty());

    interceptor.authorizeRequest(requestDetails);
  }

  @Test
  public void shouldNotAllowReadingConformanceResourceOfOtherResourceOriginWithoutScope() {
    RequestDetails requestDetails = getRequestDetailsAndConfigurePermission(RequestTypeEnum.GET, ResourceType.ValueSet, new IdType(ResourceType.ValueSet.name(), 14L), "rs");
    setScope(requestDetails, "system/Task.rs?resource-origin=Device/123");

    resourceOriginUtil.when(() -> ResourceOriginUtil.getResourceOriginDeviceId(any(IBaseResource.class)))
      .thenReturn(Optional.of(new IdType("Device", 999L)));

    assertThrows(ForbiddenOperationException.class, () -> interceptor.authorizeRequest(requestDetails));
  }

  @Test
  public void shouldNotAllowWritingConformanceResourceWithoutScope() {
    RequestDetails requestDetails = getRequestDetailsAndConfigurePermission(RequestTypeEnum.PUT, ResourceType.ValueSet, new IdType(ResourceType.ValueSet.name(), 14L), "u");
    setScope(requestDetails, "system/Task.cruds");

    resourceOriginUtil.when(() -> ResourceOriginUtil.getResourceOriginDeviceId(any(IBaseResource.class)))
      .thenReturn(Optional.empty());

    assertThrows(ForbiddenOperationException.class, () -> interceptor.authorizeRequest(requestDetails));
  }

}
