package ca.uhn.fhir.jpa.starter.koppeltaal.interceptor;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.starter.koppeltaal.config.SmartBackendServiceConfiguration;
import ca.uhn.fhir.jpa.starter.koppeltaal.service.SmartBackendServiceAuthorizationService;
import ca.uhn.fhir.jpa.starter.koppeltaal.util.ResourceOriginUtil;
import ca.uhn.fhir.rest.api.RequestTypeEnum;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.exceptions.ForbiddenOperationException;
import ca.uhn.fhir.rest.server.servlet.ServletRequestDetails;
import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.instance.model.api.IIdType;
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
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ResourceOriginAuthorizationInterceptorTest {

  private static MockedStatic<ResourceOriginUtil> resourceOriginUtil;

  private ResourceOriginAuthorizationInterceptor interceptor;
  private DaoRegistry daoRegistry;

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
            @Mock SmartBackendServiceAuthorizationService smartBackendServiceAuthorizationService,
            @Mock SmartBackendServiceConfiguration smartBackendServiceConfiguration
  ) {

    this.daoRegistry = daoRegistry;

    interceptor = new ResourceOriginAuthorizationInterceptor(
      daoRegistry,
      deviceDao,
      smartBackendServiceAuthorizationService,
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


  /**
   * Creates a configured {@link RequestDetails} and mocks the resource dao to return the mocked entity when the
   * resource-origin is requested.
   *
   * @param requestType  request type enum
   * @param resourceType resource type enum
   * @param resourceId   the id of the resource that the request is tied to. <code>null</code> for
   *                     an "ALL" call like GET /Patient
   * @param crudsValue
   * @return The configured {@link RequestDetails}.
   */
  private RequestDetails getRequestDetailsAndConfigurePermission(
    RequestTypeEnum requestType,
    ResourceType resourceType,
    IdType resourceId,
    String crudsValue,
    String... resourceOrigins) {

    ServletRequestDetails requestDetails = new ServletRequestDetails();
    requestDetails.setResourceName(resourceType.name());
    requestDetails.setRequestType(requestType);
    requestDetails.setId(resourceId);

    final IFhirResourceDao resourceDaoMock = mock(IFhirResourceDao.class);
    lenient().when(daoRegistry.getResourceDao(eq(resourceType.name())))
      .thenReturn(resourceDaoMock);

    // when the action is executed on a resource, we want the dao registry to return an actual instance of that resource type
    if (resourceId != null) {
      try {
        final IBaseResource instance = (IBaseResource) Class.forName("org.hl7.fhir.r4.model." + resourceId.getResourceType())
          .getDeclaredConstructor()
          .newInstance();

        requestDetails.setResource(instance);

        if(requestType != RequestTypeEnum.POST) {
          when(resourceDaoMock.read(any(IIdType.class), any(RequestDetails.class)))
            .thenReturn(instance);
        }
      } catch (Exception e) {
        throw new RuntimeException("Failed to create an instance of org.hl7.fhir.r4.model." + resourceId.getResourceType(), e);
      }
    }

    addPermissions(requestDetails, crudsValue, resourceOrigins);

    return requestDetails;
  }

  private RequestDetails addPermissions(ServletRequestDetails requestDetails, String crudsValue, String... resourceOrigins) {

    StringBuilder permission = new StringBuilder(String.format("system/%s.%s", requestDetails.getResourceName(), crudsValue));

    if (resourceOrigins != null && resourceOrigins.length > 0) {

      permission.append("?resource-origin=");

      for (int i = 0; i < resourceOrigins.length; i++) {
        String resourceOrigin = resourceOrigins[i];

        permission.append(resourceOrigin);

        if (i + 1 < resourceOrigins.length) {
          permission.append(",");
        }
      }
    }

    //surround the "actual" permission with 2 gibberish permissions
    String jwtWithScope = JWT.create()
      .withClaim("scope", String.format("system/Unused.cru?resource-origin=Device/123 %s system/Unused2.cd?resource-origin=Device/456", permission))
      .sign(Algorithm.HMAC256("super-secret"));

    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("Authorization", "Bearer " + jwtWithScope);

    requestDetails.setServletRequest(request);

    return requestDetails;
  }

}
