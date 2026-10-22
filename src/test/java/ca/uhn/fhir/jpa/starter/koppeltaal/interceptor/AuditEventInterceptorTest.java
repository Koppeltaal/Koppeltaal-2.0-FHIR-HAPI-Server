package ca.uhn.fhir.jpa.starter.koppeltaal.interceptor;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.starter.koppeltaal.dto.AuditEventDto;
import ca.uhn.fhir.jpa.starter.koppeltaal.service.AuditEventService;
import ca.uhn.fhir.jpa.starter.koppeltaal.util.RequestIdHolder;
import ca.uhn.fhir.rest.api.RequestTypeEnum;
import ca.uhn.fhir.rest.api.RestOperationTypeEnum;
import ca.uhn.fhir.rest.server.exceptions.ResourceVersionConflictException;
import ca.uhn.fhir.rest.server.servlet.ServletRequestDetails;
import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import org.hl7.fhir.r4.model.Device;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuditEventInterceptorTest {

  private static final String CONFLICT = "Unable to delete Patient/12 because at least one resource has a reference to this resource. "
    + "First reference found was resource Task/99 in path Task.for";

  private AuditEventInterceptor interceptor;

  private AuditEventService auditEventService;

  @BeforeEach
  @SuppressWarnings({"unchecked", "rawtypes"})
  void init() {
    DaoRegistry daoRegistry = mock(DaoRegistry.class);
    when(daoRegistry.getResourceDao(Device.class)).thenReturn((IFhirResourceDao) mock(IFhirResourceDao.class));
    auditEventService = mock(AuditEventService.class);

    interceptor = new AuditEventInterceptor(daoRegistry, auditEventService, mock(RequestIdHolder.class));
  }

  @Test
  void shouldNotAuditTheReferencingResourceOfADeleteConflict() {
    // the requester can read its own AuditEvents
    interceptor.preOutgoingException(deleteRequest("system/Patient.d?resource-origin=Device/1"), new ResourceVersionConflictException(CONFLICT));

    String diagnostics = auditedDiagnostics();
    assertFalse(diagnostics.contains("Task/99"), diagnostics);
    assertTrue(diagnostics.contains("Patient/12"), diagnostics);
  }

  @Test
  void shouldAuditTheDeleteConflictDetailsForUnrestrictedDeletePermission() {
    interceptor.preOutgoingException(deleteRequest("system/*.cruds"), new ResourceVersionConflictException(CONFLICT));

    assertTrue(auditedDiagnostics().contains("Task/99"));
  }

  private String auditedDiagnostics() {
    ArgumentCaptor<AuditEventDto> dto = ArgumentCaptor.forClass(AuditEventDto.class);
    verify(auditEventService).submitAuditEvent(dto.capture(), any());

    return dto.getValue().getOperationOutcome().getIssue().stream()
      .map(OperationOutcome.OperationOutcomeIssueComponent::getDiagnostics)
      .collect(Collectors.joining(" "));
  }

  private static ServletRequestDetails deleteRequest(String scope) {
    String token = JWT.create().withClaim("scope", scope).sign(Algorithm.HMAC256("super-secret"));

    MockHttpServletRequest request = new MockHttpServletRequest("DELETE", "/fhir/DEFAULT/Patient/12");
    request.addHeader("Authorization", "Bearer " + token);
    request.setAttribute(JwtSecurityInterceptor.VALIDATED_ACCESS_TOKEN_ATTRIBUTE, token);

    ServletRequestDetails requestDetails = new ServletRequestDetails();
    requestDetails.setServletRequest(request);
    requestDetails.setRequestType(RequestTypeEnum.DELETE);
    requestDetails.setRestOperationType(RestOperationTypeEnum.DELETE);
    requestDetails.setResourceName("Patient");
    requestDetails.setId(new IdType("Patient", 12L));
    return requestDetails;
  }
}
