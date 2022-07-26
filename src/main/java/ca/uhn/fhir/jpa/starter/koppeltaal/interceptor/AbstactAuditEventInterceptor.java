package ca.uhn.fhir.jpa.starter.koppeltaal.interceptor;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.starter.koppeltaal.dto.AuditEventDto;
import ca.uhn.fhir.jpa.starter.koppeltaal.service.AuditEventService;
import ca.uhn.fhir.jpa.starter.koppeltaal.util.ResourceOriginUtil;
import ca.uhn.fhir.rest.api.RestOperationTypeEnum;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.servlet.ServletRequestDetails;
import org.apache.commons.lang3.StringUtils;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;

/**
 *
 */
public abstract class AbstactAuditEventInterceptor {
  private static final Logger LOG = LoggerFactory.getLogger(AbstactAuditEventInterceptor.class);
  protected final AuditEventService auditEventService;
  protected final IFhirResourceDao<Device> deviceDao;
  protected final DaoRegistry daoRegistry;

  public AbstactAuditEventInterceptor(AuditEventService auditEventService, DaoRegistry daoRegistry) {
    this.auditEventService = auditEventService;
    this.deviceDao = daoRegistry.getResourceDao(Device.class);
    this.daoRegistry = daoRegistry;
  }

  private void addResources(AuditEventDto dto, IBaseResource... resources) {
    for (IBaseResource resource : resources) {
      if (resource instanceof Resource) {
        dto.addResource((Resource) resource);
      }
    }
  }

  private Device buildDevice(RequestDetails requestDetails) {
    return ResourceOriginUtil.getDevice(requestDetails, deviceDao).orElse(null);
  }

  protected void ensureTransactionGuid(RequestDetails requestDetails) {
    if (StringUtils.isBlank(requestDetails.getTransactionGuid())) {
      requestDetails.setTransactionGuid(UUID.randomUUID().toString());
    }
  }

  private String getResourceQuery(ServletRequestDetails requestDetails) {
    String completeUrl = requestDetails.getCompleteUrl();
    return StringUtils.removeStart(completeUrl, requestDetails.getFhirServerBase());
  }

  protected void setDevice(RequestDetails requestDetails, AuditEventDto dto) {
    dto.setDevice(buildDevice(requestDetails));
  }

  protected void setInteraction(ServletRequestDetails requestDetails, AuditEventDto dto) {
    dto.setRequestId(requestDetails.getRequestId());
    dto.setTraceId(requestDetails.getTransactionGuid());
    dto.setCorrelationId((String) requestDetails.getUserData().get("correlationId"));
  }

  protected boolean setRequestType(ServletRequestDetails requestDetails, AuditEventDto dto) {
    RestOperationTypeEnum restOperationType = requestDetails.getRestOperationType();
    String method = requestDetails.getServletRequest().getMethod();
    if (restOperationType != null) {
      switch (restOperationType) {
        case CREATE:
          dto.setEventType(AuditEventDto.EventType.Create);
          return true;
        case READ:
          dto.setEventType(AuditEventDto.EventType.Read);
          return true;
        case SEARCH_TYPE:
          dto.setEventType(AuditEventDto.EventType.Search);
          return true;
        case UPDATE:
          dto.setEventType(AuditEventDto.EventType.Update);
          return true;
        case DELETE:
          dto.setEventType(AuditEventDto.EventType.Delete);
          return true;
        case METADATA:
          dto.setEventType(AuditEventDto.EventType.Capability);
          return true;
        default:
          LOG.warn(String.format("Unhandled / unknown event type: %s", restOperationType));
      }
    } else if (StringUtils.isNotEmpty(method)) {
      switch (method) {
        case "POST":
          dto.setEventType(AuditEventDto.EventType.Create);
          return true;
        case "GET":
          dto.setEventType(AuditEventDto.EventType.Read); // Or Search?
          return true;
        case "PUT":
          dto.setEventType(AuditEventDto.EventType.Update);
          return true;
        case "DELETE":
          dto.setEventType(AuditEventDto.EventType.Delete);
          return true;

      }
    }
    return false;
  }

  protected void storageEvent(ServletRequestDetails requestDetails, IBaseResource resource) {
    if (!(resource instanceof AuditEvent)) {
      AuditEventDto dto = new AuditEventDto();
      if (setRequestType(requestDetails, dto)) {
        setInteraction(requestDetails, dto);
        setDevice(requestDetails, dto);
        addResources(dto, resource);
        if (resource instanceof Bundle) {
          Bundle bundle = (Bundle) resource;
          List<Bundle.BundleEntryComponent> entry = bundle.getEntry();
          if (entry != null) {
            for (Bundle.BundleEntryComponent bundleEntryComponent : entry) {
              addResources(dto, bundleEntryComponent.getResource());
            }

          }
        }
        dto.setQuery(getResourceQuery(requestDetails));
        dto.setOutcome("0");
        auditEventService.submitAuditEvent(dto, requestDetails);
      }
    }
  }

  private void storeActionKey(HttpServletRequest servletRequest, String actionKey) {
    if (StringUtils.isNotEmpty(actionKey)) {
      servletRequest.setAttribute(actionKey, true);
    }
  }

  private boolean hasActionKey(HttpServletRequest servletRequest, String actionKey) {
    return StringUtils.isNotEmpty(actionKey) && servletRequest.getAttribute(actionKey) != null;
  }

  private String getActionKey(AuditEventDto dto) {
    AuditEventDto.EventType eventType = dto.getEventType();
    if (dto.getResources().size() == 1) {
      Resource resource = dto.getResources().get(0);
      return eventType.name() + "/" + resource.getId();
    }
    return null;
  }
}
