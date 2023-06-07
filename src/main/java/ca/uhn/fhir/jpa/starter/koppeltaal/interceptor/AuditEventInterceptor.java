package ca.uhn.fhir.jpa.starter.koppeltaal.interceptor;

import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.starter.koppeltaal.dto.AuditEventDto;
import ca.uhn.fhir.jpa.starter.koppeltaal.service.AuditEventBuilder;
import ca.uhn.fhir.jpa.starter.koppeltaal.service.AuditEventService;
import ca.uhn.fhir.jpa.starter.koppeltaal.util.RequestIdHolder;
import ca.uhn.fhir.rest.api.RestOperationTypeEnum;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.exceptions.BaseServerResponseException;
import ca.uhn.fhir.rest.server.servlet.ServletRequestDetails;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;

/**
 *
 */
@Component
@Interceptor
public class AuditEventInterceptor extends AbstractAuditEventInterceptor {
  private static final Logger LOG = LoggerFactory.getLogger(AuditEventInterceptor.class);
  private final RequestIdHolder requestIdHolder;

  public AuditEventInterceptor(DaoRegistry daoRegistry, AuditEventService auditEventService, RequestIdHolder requestIdHolder) {
    super(auditEventService, daoRegistry);

    this.requestIdHolder = requestIdHolder;
  }

  @Hook(Pointcut.STORAGE_PRECOMMIT_RESOURCE_CREATED)
  public void creating(ServletRequestDetails requestDetails, IBaseResource resource) {
    storageEvent(requestDetails, resource);
  }

  @Hook(Pointcut.STORAGE_PRECOMMIT_RESOURCE_DELETED)
  public void deleting(ServletRequestDetails requestDetails, IBaseResource resource) {
    storageEvent(requestDetails, resource);
  }

  @Hook(value = Pointcut.SERVER_PRE_PROCESS_OUTGOING_EXCEPTION, order = 100)
  public void preOutgoingException(ServletRequestDetails servletRequestDetails, Throwable throwable) {

    try {
      if(throwable instanceof BaseServerResponseException) {
        BaseServerResponseException serverResponseException = (BaseServerResponseException) throwable;

        int statusCode = serverResponseException.getStatusCode();
        boolean isSeriousFailure = statusCode >= 500;
        boolean isMinorFailure = statusCode >= 400 && !isSeriousFailure;
        if(!isSeriousFailure && !isMinorFailure) {
          LOG.error("Found response code [{}] in SERVER_PRE_PROCESS_OUTGOING_EXCEPTION, assumed never be smaller than 400!", statusCode);
        }

        AuditEventDto dto = new AuditEventDto();
        if (setRequestType(servletRequestDetails, dto)) {
          setInteraction(servletRequestDetails, dto);
          setAgent(servletRequestDetails, dto, AuditEventBuilder.CODING_SOURCE_ROLE_ID);
          dto.setOutcome(isSeriousFailure ? "8" : "4");

          OperationOutcome operationOutcome = (OperationOutcome) serverResponseException.getOperationOutcome();
          if(operationOutcome == null) {
            operationOutcome = generateOperationOutcome(serverResponseException.getMessage());
          }

          dto.setOperationOutcome(operationOutcome);
          auditEventService.submitAuditEvent(dto, servletRequestDetails);
        }
      } else {
        LOG.warn("AuditEvent in SERVER_PRE_PROCESS_OUTGOING_EXCEPTION not an instance of BaseServerResponseException - forging OperationOutcome");
        AuditEventDto dto = new AuditEventDto();
        if (setRequestType(servletRequestDetails, dto)) {
          setInteraction(servletRequestDetails, dto);
          setAgent(servletRequestDetails, dto, AuditEventBuilder.CODING_SOURCE_ROLE_ID);
          OperationOutcome operationOutcome = generateOperationOutcome(throwable.getMessage());
          dto.setOperationOutcome(operationOutcome);
          dto.setOutcome("8");
          auditEventService.submitAuditEvent(dto, servletRequestDetails);
        }
      }
    } catch (Exception e) {
      // this is a best-effort to create an audit event, but we do not want it to impact the original error response.
      // for example, if the Device is not found, it will override the original error code with a 404
      LOG.error("Failed to create an AuditEvent in the SERVER_OUTGOING_FAILURE_OPERATIONOUTCOME Pointcut", e);
    }
  }

  @NotNull
  private static OperationOutcome generateOperationOutcome(String errorDescription) {
    OperationOutcome operationOutcome = new OperationOutcome();
    OperationOutcome.OperationOutcomeIssueComponent issue = new OperationOutcome.OperationOutcomeIssueComponent();
    issue.setSeverity(OperationOutcome.IssueSeverity.ERROR);
    issue.setCode(OperationOutcome.IssueType.EXCEPTION);
    issue.setDiagnostics(errorDescription);

    operationOutcome.setIssue(Collections.singletonList(issue));
    return operationOutcome;
  }

  @Hook(Pointcut.SERVER_OUTGOING_RESPONSE)
  public void outgoingResponse(RequestDetails responseDetails, ServletRequestDetails servletRequestDetails, IBaseResource resource) {
    RestOperationTypeEnum restOperationType = responseDetails.getRestOperationType();
    if (restOperationType != RestOperationTypeEnum.CREATE
      && restOperationType != RestOperationTypeEnum.UPDATE
      && restOperationType != RestOperationTypeEnum.DELETE
      && resource.getIdElement() != null
      && resource.getIdElement().getValue() != null) {
      storageEvent(servletRequestDetails, resource);
    }
  }

  @Hook(Pointcut.SERVER_INCOMING_REQUEST_POST_PROCESSED)
  public void start(ServletRequestDetails requestDetails) {

    // The requestId is always set @ ca.uhn.fhir.jpa.starter.koppeltaal.KoppeltaalRestfulServer.getOrCreateRequestId()
    String requestId = requestDetails.getRequestId();

    requestIdHolder.addMapping(requestDetails.getTransactionGuid(), requestId, requestDetails.getTenantId());

    LOG.info(String.format("Incoming request, traceId='%s', requestId='%s', correlationId='%s'", requestDetails.getTransactionGuid(), requestId, requestDetails.getUserData().get("correlationId")));
  }

  @Hook(Pointcut.STORAGE_PRECOMMIT_RESOURCE_UPDATED)
  public void updating(ServletRequestDetails requestDetails, IBaseResource resource) {
    storageEvent(requestDetails, resource);
  }
}
