package ca.uhn.fhir.jpa.starter.koppeltaal.interceptor;

import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.starter.koppeltaal.dto.AuditEventDto;
import ca.uhn.fhir.jpa.starter.koppeltaal.service.AuditEventService;
import ca.uhn.fhir.jpa.starter.koppeltaal.util.RequestIdHolder;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.api.server.ResponseDetails;
import ca.uhn.fhir.rest.server.servlet.ServletRequestDetails;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.StringUtils;
import org.hl7.fhir.instance.model.api.IBaseOperationOutcome;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;

/**
 *
 */
@Component
@Interceptor
public class AuditEventInterceptor extends AbstactAuditEventInterceptor {
  private static final Logger LOG = LoggerFactory.getLogger(AuditEventInterceptor.class);

  public AuditEventInterceptor(DaoRegistry daoRegistry, AuditEventService auditEventService) {
    super(auditEventService, daoRegistry);

  }

  @Hook(Pointcut.STORAGE_PRECOMMIT_RESOURCE_CREATED)
  public void creating(ServletRequestDetails requestDetails, IBaseResource resource) {
    storageEvent(requestDetails, resource);
  }

  @Hook(Pointcut.STORAGE_PRECOMMIT_RESOURCE_DELETED)
  public void deleting(ServletRequestDetails requestDetails, IBaseResource resource) {
    storageEvent(requestDetails, resource);
  }

  @Hook(Pointcut.SERVER_OUTGOING_FAILURE_OPERATIONOUTCOME)
  public void outgoingException(ServletRequestDetails requestDetails, IBaseOperationOutcome operationOutcome) {
    AuditEventDto dto = new AuditEventDto();
    if (setRequestType(requestDetails, dto)) {
      setInteraction(requestDetails, dto);
      setDevice(requestDetails, dto);
      setOutcome(operationOutcome, dto);
      auditEventService.submitAuditEvent(dto, requestDetails);
    }
  }

  @Hook(Pointcut.SERVER_OUTGOING_RESPONSE)
  public void outgoingResponse(ServletRequestDetails requestDetails, IBaseResource resource, ResponseDetails responseDetails) {
    if (resource.getIdElement() != null
      && resource.getIdElement().getValue() != null) {
      storageEvent(requestDetails, resource);
    }
  }

  @Hook(Pointcut.SERVER_INCOMING_REQUEST_POST_PROCESSED)
  public void start(ServletRequestDetails requestDetails) {

    HttpServletRequest servletRequest = requestDetails.getServletRequest();
    String traceId = servletRequest.getHeader("X-Trace-Id");

    // Set the requestId if not set.
    String requestId = requestDetails.getRequestId();
    if (StringUtils.isBlank(requestId)) {
      requestId = RandomStringUtils.randomAlphanumeric(16);
      requestDetails.setRequestId(requestId);
    }

    // Set the traceId, generate one if none exists.
    if (StringUtils.isNotEmpty(traceId)) {
      requestDetails.setTransactionGuid(traceId);
    } else {
      requestDetails.setTransactionGuid(UUID.randomUUID().toString());
    }

    RequestIdHolder.addMapping(requestDetails.getTransactionGuid(), requestId);

    LOG.info(String.format("Incoming request, traceId='%s', requestId='%s', correlationId='%s'", requestDetails.getTransactionGuid(), requestId, requestDetails.getUserData().get("correlationId")));
  }

  @Hook(Pointcut.STORAGE_PRECOMMIT_RESOURCE_UPDATED)
  public void updating(ServletRequestDetails requestDetails, IBaseResource resource) {
    storageEvent(requestDetails, resource);
  }

  private String getTransactionGuid(RequestDetails requestDetails) {
    String transactionGuid = requestDetails.getTransactionGuid();
    if (StringUtils.isEmpty(transactionGuid)) {
      transactionGuid = UUID.randomUUID().toString();
      requestDetails.setTransactionGuid(transactionGuid);
    }
    return transactionGuid;
  }

  private void setOutcome(IBaseOperationOutcome operationOutcome, AuditEventDto dto) {
    if (operationOutcome instanceof OperationOutcome) {
      List<OperationOutcome.OperationOutcomeIssueComponent> issue = ((OperationOutcome) operationOutcome).getIssue();
      if (!issue.isEmpty()) {
        OperationOutcome.OperationOutcomeIssueComponent outcome = issue.get(0);
        OperationOutcome.IssueSeverity severity = outcome.getSeverity();
        switch (severity) {
          case WARNING:
          case ERROR:
            dto.setOutcome("4");
            break;
          case INFORMATION:
          case NULL:
          default:
            dto.setOutcome("0");
            break;

        }
      }
    }
  }

}
