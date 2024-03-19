package ca.uhn.fhir.jpa.starter.koppeltaal.interceptor;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.starter.koppeltaal.dto.AuditEventDto;
import ca.uhn.fhir.jpa.starter.koppeltaal.service.AuditEventBuilder;
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
import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;

/**
 *
 */
public abstract class AbstractAuditEventInterceptor {
  private static final Logger LOG = LoggerFactory.getLogger(AbstractAuditEventInterceptor.class);
  protected final AuditEventService auditEventService;
  protected final IFhirResourceDao<Device> deviceDao;
  protected final DaoRegistry daoRegistry;

  public AbstractAuditEventInterceptor(AuditEventService auditEventService, DaoRegistry daoRegistry) {
    this.auditEventService = auditEventService;
    this.deviceDao = daoRegistry.getResourceDao(Device.class);
    this.daoRegistry = daoRegistry;
  }

  private void addResources(AuditEventDto dto, IBaseResource... resources) {
    for (IBaseResource resource : resources) {
      if (resource instanceof Resource) {

        if (StringUtils.isBlank(resource.getIdElement().getResourceType())) { // for some reason the Bundle references
                                                                              // have no resource type..
          resource.getIdElement()
              .setValue(String.format("%s/%s", resource.fhirType(), resource.getIdElement().getIdPart()));
        }

        Reference reference = new Reference((Resource) resource);
        reference.setType(resource.fhirType());

        dto.addResource(reference);
      }
    }
  }

  private Device buildDevice(RequestDetails requestDetails) {
    return ResourceOriginUtil.getDevice(requestDetails, deviceDao).orElse(null);
  }

  private String getResourceQuery(ServletRequestDetails requestDetails) {
    String completeUrl = requestDetails.getCompleteUrl();
    return StringUtils.removeStart(completeUrl, requestDetails.getFhirServerBase());
  }

  protected void setAgent(RequestDetails requestDetails, AuditEventDto dto, Coding type) {
    dto.addAgent(new Reference(buildDevice(requestDetails)), type);
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
        case GET_PAGE:
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

    try {
      if (!(resource instanceof AuditEvent)) {
        AuditEventDto dto = new AuditEventDto();
        addResources(dto, resource);
        if (setRequestType(requestDetails, dto)) {
          setInteraction(requestDetails, dto);
          setAgent(requestDetails, dto, AuditEventBuilder.CODING_SOURCE_ROLE_ID);
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
          dto.setSite(getSite(requestDetails));
          dto.setOutcome("0");
          auditEventService.submitAuditEvent(dto, requestDetails);
        }
      }
    } catch (Exception e) {
      // this is a best-effort to create an audit event, but we do not want it to
      // impact the original error response.
      // for example, if the Device is not found, it will override the original error
      // code with a 404
      if (resource != null) {
        LOG.error(String.format("Failed to store AuditEvent for resource [%s]", resource.getIdElement().getValue()), e);
      } else {
        LOG.error("Failed to store AuditEvent for resource [null]", e);
      }
    }
  }

  private String getSite(ServletRequestDetails requestDetails) {
    HttpServletRequest servletRequest = requestDetails.getServletRequest();
    String forwardHost = servletRequest.getHeader("X-Forwarded-Host");
    String forwardProto = servletRequest.getHeader("X-Forwarded-Proto");
    if (StringUtils.isNotEmpty(forwardHost)) {
      return StringUtils.defaultString(forwardProto, "https") + "://" + forwardHost;
    }
    String requestUrl = servletRequest.getRequestURL().toString();
    try {
      URL url = new URL(requestUrl);
      return url.getProtocol() + "://" + url.getHost();
    } catch (MalformedURLException e) {
      LOG.error("Failed to parse request URL: " + requestUrl, e);
      return "http://localhost";
    }
  }

}
