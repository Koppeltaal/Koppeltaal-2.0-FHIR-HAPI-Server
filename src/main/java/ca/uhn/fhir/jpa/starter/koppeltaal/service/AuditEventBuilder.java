package ca.uhn.fhir.jpa.starter.koppeltaal.service;

import ca.uhn.fhir.interceptor.model.RequestPartitionId;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.api.model.DaoMethodOutcome;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import jakarta.annotation.PostConstruct;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.jpa.starter.koppeltaal.config.FhirServerAuditLogConfiguration;
import ca.uhn.fhir.jpa.starter.koppeltaal.dto.AuditEventDto;
import ca.uhn.fhir.jpa.starter.koppeltaal.dto.AuditEventDto.EventType;
import ca.uhn.fhir.model.dstu2.composite.IdentifierDt;
import ca.uhn.fhir.rest.api.server.IBundleProvider;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import org.apache.commons.lang3.StringUtils;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r4.model.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import static ca.uhn.fhir.jpa.starter.koppeltaal.util.ResourceOriginUtil.RESOURCE_ORIGIN_SYSTEM;
import static java.util.Collections.singletonList;

/**
 *
 */
@Component
public class AuditEventBuilder {

	public static final Coding CODING_REST = new Coding("http://terminology.hl7.org/CodeSystem/audit-event-type", "rest", "RESTful Operation");
	public static final Coding CODING_TRANSMIT = new Coding("http://terminology.hl7.org/CodeSystem/iso-21089-lifecycle", "transmit", "Transmit Record Lifecycle Event");
	public static final Coding CODING_INTERACTION_READ = new Coding("http://hl7.org/fhir/restful-interaction", "read", "read");
	public static final Coding CODING_INTERACTION_UPDATE = new Coding("http://hl7.org/fhir/restful-interaction", "update", "update");
	public static final Coding CODING_INTERACTION_DELETE = new Coding("http://hl7.org/fhir/restful-interaction", "delete", "delete");
	public static final Coding CODING_INTERACTION_CREATE = new Coding("http://hl7.org/fhir/restful-interaction", "create", "create");
	public static final Coding CODING_INTERACTION_SEARCH = new Coding("http://hl7.org/fhir/restful-interaction", "search", "search");
	public static final Coding CODING_INTERACTION_CAPABILITIES = new Coding("http://hl7.org/fhir/restful-interaction", "capabilities", "capabilities");
	public static final Coding CODING_APPLICATION = new Coding("http://dicom.nema.org/resources/ontology/DCM", "110150", "Application");
	public static final Coding CODING_APPLICATION_LAUNCHER = new Coding("http://dicom.nema.org/resources/ontology/DCM", "110151", "Application Launcher");
	public static final Coding CODING_DESTINATION_ROLE_ID = new Coding("http://dicom.nema.org/resources/ontology/DCM", "110152", "Destination Role ID");
	public static final Coding CODING_SOURCE_ROLE_ID = new Coding("http://dicom.nema.org/resources/ontology/DCM", "110153", "Source Role ID");

  public final static String META_PROFILE_URL = "http://koppeltaal.nl/fhir/StructureDefinition/KT2AuditEvent";

	private final FhirServerAuditLogConfiguration fhirServerAuditLogConfiguration;
	private final IFhirResourceDao<Device> deviceDao;

	private  Device self;

	public AuditEventBuilder(DaoRegistry daoRegistry, FhirServerAuditLogConfiguration fhirServerAuditLogConfiguration) {
		this.deviceDao = daoRegistry.getResourceDao(Device.class);
		this.fhirServerAuditLogConfiguration = fhirServerAuditLogConfiguration;
	}

	@PostConstruct
	public void init() {
		self = findObserver();
		if (self == null) {
			self = createObserver();
		}
	}

	AuditEvent build(AuditEventDto dto) {
    AuditEvent auditEvent = new AuditEvent();
    AuditEventDto.EventType eventType = dto.getEventType();
    buildEventType(auditEvent, eventType);
    List<Reference> resources = dto.getResources();
    for (Reference resource : resources) {
      AuditEvent.AuditEventEntityComponent entity = buildAuditEventEntityComponent(resource, dto);
      if (dto.getEventType() == AuditEventDto.EventType.Delete) {
        entity.setWhat(null); //remove as it's not allowed to reference to deleted objects
      }
      auditEvent.addEntity(entity);
    }
    auditEvent.setSource(buildEventSource(dto.getSite()));
    auditEvent.setRecorded(dto.getDateTime());

    getResourceOriginExtension(dto)
      .ifPresent(auditEvent::addExtension);

		auditEvent.addExtension("http://koppeltaal.nl/fhir/StructureDefinition/trace-id", new IdType(dto.getTraceId()));
		auditEvent.addExtension("http://koppeltaal.nl/fhir/StructureDefinition/request-id", new IdType(dto.getRequestId()));
		auditEvent.addExtension("http://koppeltaal.nl/fhir/StructureDefinition/correlation-id", new IdType(dto.getCorrelationId()));

    if(dto.getOperationOutcome() != null) {
      AuditEvent.AuditEventEntityComponent entity = buildAuditEventEntityComponent(newReference(dto.getOperationOutcome()), dto);
      entity.setWhat(null); //remove ad OperationOutcomes aren't persisted and can't be referenced to
      auditEvent.addEntity(entity);
      auditEvent.setOutcomeDesc(
        dto.getOperationOutcome().getIssue().stream()
          .map(issue -> issue.getDiagnostics() + "\n")
          .collect(Collectors.joining())
      );
      auditEvent.setOutcome(AuditEvent.AuditEventOutcome.fromCode(dto.getOutcome()));
    }

    if (StringUtils.isNotEmpty(dto.getOutcome())) {
      auditEvent.setOutcome(AuditEvent.AuditEventOutcome.fromCode(dto.getOutcome()));
    }

    buildAgents(auditEvent, dto);
    setMetaWithProfileUrl(auditEvent);
    return auditEvent;
  }

  private void buildAgents(AuditEvent auditEvent, AuditEventDto dto) {
    for (AuditEventDto.AgentAndTypeDto agentDto : dto.getAgents()) {
      if (auditEvent.getType().equalsShallow(CODING_TRANSMIT)) {
        // This is a Subscription Notification
        auditEvent.addAgent(buildAgents(agentDto.getAgent(), agentDto.getType(), agentDto.isRequester()));
      } else if (auditEvent.getType().equalsShallow(CODING_REST)) {
        // This event is a REST operation
        auditEvent.addAgent(buildAgents(agentDto.getAgent(), agentDto.getType(), agentDto.isRequester()));
      }
    }
  }

  @NotNull
  private Optional<Extension> getResourceOriginExtension(AuditEventDto dto) {
    for (AuditEventDto.AgentAndTypeDto agent : dto.getAgents()) {
      final Extension resourceOriginExtension = new Extension();
      resourceOriginExtension.setUrl(RESOURCE_ORIGIN_SYSTEM);
      resourceOriginExtension.setValue(agent.getAgent());
      return Optional.of(resourceOriginExtension);
    }
    return Optional.empty();
  }

  private void setMetaWithProfileUrl(AuditEvent auditEvent) {
    Meta profileMeta = new Meta();
    profileMeta.setProfile(Collections.singletonList(new CanonicalType(META_PROFILE_URL)));
    auditEvent.setMeta(profileMeta);
  }

  private AuditEvent.AuditEventAgentComponent buildAgents(Reference device, Coding role, boolean requestor) {
    AuditEvent.AuditEventAgentComponent rv = new AuditEvent.AuditEventAgentComponent();
    rv.setWho(device);
    rv.setType(new CodeableConcept(role));
    rv.setRequestor(requestor);
    return rv;
  }

  private AuditEvent.AuditEventEntityComponent buildAuditEventEntityComponent(Reference reference, AuditEventDto auditEvent) {
    AuditEvent.AuditEventEntityComponent component = new AuditEvent.AuditEventEntityComponent();
    component.setWhat(reference);
    String type = getTypeFromReference(reference);
    component.setType(new Coding("http://hl7.org/fhir/resource-types", type, type));
    String query = auditEvent.getQuery();
    if (StringUtils.isNotEmpty(query)) {
      component.setQuery(query.getBytes(StandardCharsets.UTF_8));
    } else {
      component.setName(type); //it's not allowed to both set the name and query http://hl7.org/fhir/R4B/auditevent-definitions.html#AuditEvent.entity.name
    }
    if (auditEvent.getEventType() == EventType.Search && "Bundle".equals(type)) {
      component.setRole(new Coding("http://terminology.hl7.org/CodeSystem/object-role", "24", "Query"));
    }
    return component;
	}

  @Nullable
  private static String getTypeFromReference(Reference reference) {
    String type = reference.getType();
    if (StringUtils.isEmpty(type)) {
      IIdType referenceElement = reference.getReferenceElement();
      if (!referenceElement.isEmpty()) {
        type = referenceElement.getResourceType();
      } else {
        IBaseResource resource = reference.getResource();
        if (resource != null) {
          IIdType idElement = resource.getIdElement();
          if (!idElement.isEmpty()) {
            type = idElement.getResourceType();
          }
        }
      }
    }
    return type;
  }

  private AuditEvent.AuditEventSourceComponent buildEventSource(String site) {
		return new AuditEvent.AuditEventSourceComponent()
			.setSite(site)
			.setObserver(newReference(self));
	}

	private void buildEventType(AuditEvent auditEvent, AuditEventDto.EventType eventType) {
		if (eventType == AuditEventDto.EventType.SendNotification) {
      auditEvent.setType(CODING_TRANSMIT);
      auditEvent.setSubtype(Collections.emptyList());
      auditEvent.setAction(AuditEvent.AuditEventAction.E);
    } else {
			auditEvent.setType(CODING_REST);
			switch (eventType) {
				case Create:
					auditEvent.setSubtype(singletonList(CODING_INTERACTION_CREATE));
					auditEvent.setAction(AuditEvent.AuditEventAction.C);
					break;
				case Read:
					auditEvent.setSubtype(singletonList(CODING_INTERACTION_READ));
					auditEvent.setAction(AuditEvent.AuditEventAction.R);
					break;
				case Search:
					auditEvent.setSubtype(singletonList(CODING_INTERACTION_SEARCH));
					auditEvent.setAction(AuditEvent.AuditEventAction.R);
					break;
				case Update:
					auditEvent.setSubtype(singletonList(CODING_INTERACTION_UPDATE));
					auditEvent.setAction(AuditEvent.AuditEventAction.U);
					break;
				case Delete:
					auditEvent.setSubtype(singletonList(CODING_INTERACTION_DELETE));
					auditEvent.setAction(AuditEvent.AuditEventAction.D);
					break;
				case Capability:
					auditEvent.setSubtype(singletonList(CODING_INTERACTION_CAPABILITIES));
					auditEvent.setAction(AuditEvent.AuditEventAction.R);
					break;
			}
		}
	}

	private Device createObserver() {
    //FIXME: This SHOULD match the Device instance that is linked to its own SMART backend service
		String system = fhirServerAuditLogConfiguration.getObserver().getIdentifier().getSystem();
		String value = fhirServerAuditLogConfiguration.getObserver().getIdentifier().getValue();
		Device myDevice = new Device();
    myDevice.setId(UUID.randomUUID().toString());
		Identifier identifier = new Identifier();
		identifier.setSystem(system);
		identifier.setValue(value);
		myDevice.setIdentifier(Collections.singletonList(identifier));

    SystemRequestDetails theRequestDetails = new SystemRequestDetails();
    theRequestDetails.setRequestPartitionId(
      RequestPartitionId.defaultPartition(LocalDate.now())
    );

    DaoMethodOutcome outcome = deviceDao.update(myDevice, theRequestDetails);
		return (Device) outcome.getResource();
	}

	private Device findObserver() {
		String system = fhirServerAuditLogConfiguration.getObserver().getIdentifier().getSystem();
		String value = fhirServerAuditLogConfiguration.getObserver().getIdentifier().getValue();
		RequestDetails requestDetails = new SystemRequestDetails();
    requestDetails.setTenantId("DEFAULT");
		SearchParameterMap map = new SearchParameterMap();
		map.add("identifier", new IdentifierDt(system, value));
		IBundleProvider search = deviceDao.search(map, requestDetails);
		if (!search.isEmpty()) {
			return (Device) search.getResources(0, 1).get(0);
		}
		return null;
	}

	@NotNull
	private Reference newReference(@NotNull Resource entity) {
		Reference reference = new Reference();
		String id = entity.getId();
		String type = entity.fhirType();
		String ref;
		if (StringUtils.startsWith(id, type + "/")) {
			ref = id;
		} else {
			ref = type + "/" + id;
		}
		reference.setReference(ref);
		reference.setType(type);
		return reference;
	}
}
