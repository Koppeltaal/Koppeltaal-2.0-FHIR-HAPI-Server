package ca.uhn.fhir.jpa.starter.koppeltaal.interceptor;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.starter.koppeltaal.util.ResourceOriginUtil;
import ca.uhn.fhir.rest.api.RequestTypeEnum;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.servlet.ServletRequestDetails;
import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.ResourceType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.mockito.MockedStatic;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.when;

public class BaseResourceOriginTest {

  static MockedStatic<ResourceOriginUtil> resourceOriginUtil;
  DaoRegistry daoRegistry;

  @BeforeAll
  public static void initAll() {
    resourceOriginUtil = mockStatic(ResourceOriginUtil.class);
  }

  @AfterAll
  public static void afterAll() {
    resourceOriginUtil.close();
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
  RequestDetails getRequestDetailsAndConfigurePermission(
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

  RequestDetails addPermissions(ServletRequestDetails requestDetails, String crudsValue, String... resourceOrigins) {

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
