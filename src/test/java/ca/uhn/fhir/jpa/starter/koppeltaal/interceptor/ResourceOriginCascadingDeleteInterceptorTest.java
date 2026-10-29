package ca.uhn.fhir.jpa.starter.koppeltaal.interceptor;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.interceptor.api.IInterceptorBroadcaster;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.api.model.DeleteConflict;
import ca.uhn.fhir.jpa.api.model.DeleteConflictList;
import ca.uhn.fhir.jpa.delete.ThreadSafeResourceDeleterSvc;
import ca.uhn.fhir.model.primitive.IdDt;
import ca.uhn.fhir.rest.api.Constants;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.api.server.storage.TransactionDetails;
import ca.uhn.fhir.rest.server.exceptions.ForbiddenOperationException;
import ca.uhn.fhir.rest.server.servlet.ServletRequestDetails;
import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r4.model.CareTeam;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Task;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Map;

import static ca.uhn.fhir.jpa.starter.koppeltaal.util.ResourceOriginUtil.RESOURCE_ORIGIN_SYSTEM;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ResourceOriginCascadingDeleteInterceptorTest {

  private ResourceOriginCascadingDeleteInterceptor interceptor;

  private DaoRegistry daoRegistry;

  private ThreadSafeResourceDeleterSvc deleter;

  @BeforeEach
  @SuppressWarnings({"unchecked", "rawtypes"})
  void init() {
    daoRegistry = mock(DaoRegistry.class);

    IFhirResourceDao taskDao = mock(IFhirResourceDao.class);
    when(taskDao.read(any(IIdType.class), any(RequestDetails.class))).thenReturn(withResourceOrigin(new Task(), "Task/1", "Device/2"));
    when(daoRegistry.getResourceDao("Task")).thenReturn(taskDao);

    IFhirResourceDao careTeamDao = mock(IFhirResourceDao.class);
    when(careTeamDao.read(any(IIdType.class), any(RequestDetails.class))).thenReturn(withResourceOrigin(new CareTeam(), "CareTeam/1", "Device/1"));
    when(daoRegistry.getResourceDao("CareTeam")).thenReturn(careTeamDao);

    deleter = mock(ThreadSafeResourceDeleterSvc.class);

    interceptor = new ResourceOriginCascadingDeleteInterceptor(
      FhirContext.forR4Cached(), daoRegistry, mock(IInterceptorBroadcaster.class), deleter, true);
  }

  @Test
  void shouldNotCascadeWhenTheTokenHasNoScope() {
    ServletRequestDetails requestDetails = requestDetailsWithToken(JWT.create().withClaim("azp", "domain-admin").sign(Algorithm.HMAC256("super-secret")));
    requestDetails.setParameters(Map.of(Constants.PARAMETER_CASCADE_DELETE, new String[]{Constants.CASCADE_DELETE}));

    assertThrows(ForbiddenOperationException.class, () ->
      interceptor.handleDeleteConflicts(conflicts("Task/1"), requestDetails, new TransactionDetails())
    );
  }

  @Test
  void shouldNotCheckWhenSecurityIsDisabled() {
    ResourceOriginCascadingDeleteInterceptor withoutSecurity = new ResourceOriginCascadingDeleteInterceptor(
      FhirContext.forR4Cached(), daoRegistry, mock(IInterceptorBroadcaster.class), deleter, false);
    ServletRequestDetails requestDetails = new ServletRequestDetails();
    requestDetails.setServletRequest(new MockHttpServletRequest());
    requestDetails.setParameters(Map.of(Constants.PARAMETER_CASCADE_DELETE, new String[]{Constants.CASCADE_DELETE}));

    assertNotNull(withoutSecurity.handleDeleteConflicts(conflicts("Task/1"), requestDetails, new TransactionDetails()));
    withoutSecurity.authorizeCascadedDelete(requestDetails, conflicts("Task/1"), withResourceOrigin(new Task(), "Task/1", "Device/2"));
  }

  @Test
  void shouldAuthorizeEveryResourceThatIsActuallyDeleted() {
    // runs right before every cascaded delete, also when the deleter retries
    RequestDetails requestDetails = cascadingDelete("system/Task.d?resource-origin=Device/1");

    interceptor.authorizeCascadedDelete(requestDetails, conflicts("Task/1"), withResourceOrigin(new Task(), "Task/1", "Device/1"));

    assertThrows(ForbiddenOperationException.class, () ->
      interceptor.authorizeCascadedDelete(requestDetails, conflicts("Task/2"), withResourceOrigin(new Task(), "Task/2", "Device/2"))
    );
  }

  @Test
  void shouldNotCascadeToResourcesOfOtherResourceOrigins() {
    RequestDetails requestDetails = cascadingDelete("system/Patient.d?resource-origin=Device/1 system/Task.d?resource-origin=Device/1");

    assertThrows(ForbiddenOperationException.class, () ->
      interceptor.handleDeleteConflicts(conflicts("Task/1"), requestDetails, new TransactionDetails())
    );
    verify(deleter, never()).delete(any(), any(), any());
  }

  @Test
  void shouldNotCascadeToResourceTypesWithoutDeletePermission() {
    RequestDetails requestDetails = cascadingDelete("system/Patient.d?resource-origin=Device/1 system/CareTeam.r?resource-origin=Device/1");

    assertThrows(ForbiddenOperationException.class, () ->
      interceptor.handleDeleteConflicts(conflicts("CareTeam/1"), requestDetails, new TransactionDetails())
    );
  }

  @Test
  void shouldCascadeToPermittedResources() {
    RequestDetails requestDetails = cascadingDelete("system/Patient.d?resource-origin=Device/1 system/Task.d?resource-origin=Device/2 system/CareTeam.d?resource-origin=Device/1");

    assertNotNull(interceptor.handleDeleteConflicts(conflicts("Task/1", "CareTeam/1"), requestDetails, new TransactionDetails()));
    verify(deleter).delete(any(), any(), any());
  }

  @Test
  void shouldNotCheckWhenTheRequestDoesNotCascade() {
    ServletRequestDetails requestDetails = requestDetails("system/Patient.d?resource-origin=Device/1");

    // the conflicts remain, so the delete fails with a conflict like before
    assertNull(interceptor.handleDeleteConflicts(conflicts("Task/1"), requestDetails, new TransactionDetails()));
    verify(deleter, never()).delete(any(), any(), any());
  }

  private static RequestDetails cascadingDelete(String scope) {
    ServletRequestDetails requestDetails = requestDetails(scope);
    requestDetails.setParameters(Map.of(Constants.PARAMETER_CASCADE_DELETE, new String[]{Constants.CASCADE_DELETE}));
    return requestDetails;
  }

  private static ServletRequestDetails requestDetails(String scope) {
    return requestDetailsWithToken(JWT.create().withClaim("scope", scope).sign(Algorithm.HMAC256("super-secret")));
  }

  private static ServletRequestDetails requestDetailsWithToken(String token) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("Authorization", "Bearer " + token);
    request.setAttribute(JwtSecurityInterceptor.VALIDATED_ACCESS_TOKEN_ATTRIBUTE, token);

    ServletRequestDetails requestDetails = new ServletRequestDetails();
    requestDetails.setServletRequest(request);
    return requestDetails;
  }

  private static DeleteConflictList conflicts(String... sourceIds) {
    DeleteConflictList conflicts = new DeleteConflictList();
    for (String sourceId : sourceIds) {
      conflicts.add(new DeleteConflict(new IdDt(sourceId), "reference", new IdDt("Patient/12")));
    }
    return conflicts;
  }

  private static <T extends org.hl7.fhir.r4.model.DomainResource> T withResourceOrigin(T resource, String id, String resourceOrigin) {
    resource.setId(id);
    resource.addExtension(new Extension(RESOURCE_ORIGIN_SYSTEM, new Reference(resourceOrigin)));
    return resource;
  }
}
