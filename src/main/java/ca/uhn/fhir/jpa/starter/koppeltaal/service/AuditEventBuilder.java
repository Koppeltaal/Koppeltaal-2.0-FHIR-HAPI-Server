package ca.uhn.fhir.jpa.starter.koppeltaal.service;

import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.api.model.DaoMethodOutcome;
import ca.uhn.fhir.jpa.partition.SystemRequestDetails;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.jpa.starter.koppeltaal.config.FhirServerAuditLogConfiguration;
import ca.uhn.fhir.jpa.starter.koppeltaal.dto.AuditEventDto;
import ca.uhn.fhir.model.dstu2.composite.IdentifierDt;
import ca.uhn.fhir.rest.api.server.IBundleProvider;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import org.apache.commons.lang3.StringUtils;
import org.hl7.fhir.r4.model.*;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

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
	public static final Coding CODING_INTERACTION_SEND_NOTIFICATION = new Coding("http://terminology.hl7.org/CodeSystem/iso-21089-lifecycle", "transmit", "Transmit Record Lifecycle Event");
	public static final Coding CODING_APPLICATION = new Coding("http://dicom.nema.org/resources/ontology/DCM", "110150", "Application");
	public static final Coding CODING_APPLICATION_LAUNCHER = new Coding("http://dicom.nema.org/resources/ontology/DCM", "110151", "Application Launcher");
	public static final Coding CODING_DESTINATION_ROLE_ID = new Coding("http://dicom.nema.org/resources/ontology/DCM", "110152", "Destination Role ID");
	public static final Coding CODING_SOURCE_ROLE_ID = new Coding("http://dicom.nema.org/resources/ontology/DCM", "110153", "Source Role ID");

  public final static String META_PROFILE_URL = "http://koppeltaal.nl/fhir/StructureDefinition/KT2AuditEvent";

	private final FhirServerAuditLogConfiguration fhirServerAuditLogConfiguration;
	private final IFhirResourceDao<Device> deviceDao;

	private  Device self;

	public AuditEventBuilder(IFhirResourceDao<Device> deviceDao, FhirServerAuditLogConfiguration fhirServerAuditLogConfiguration) {
		this.deviceDao = deviceDao;
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
		List<Resource> resources = dto.getResources();
		for (Resource resource : resources) {
			auditEvent.addEntity(buildAuditEventEntityComponent(resource, dto));
		}
		auditEvent.setSource(buildEventSource());
		auditEvent.setRecorded(dto.getDateTime());

    getResourceOriginExtension(dto)
      .ifPresent(auditEvent::addExtension);

		auditEvent.addExtension("http://koppeltaal.nl/fhir/StructureDefinition/trace-id", new IdType(dto.getTraceId()));
		auditEvent.addExtension("http://koppeltaal.nl/fhir/StructureDefinition/request-id", new IdType(dto.getRequestId()));
		auditEvent.addExtension("http://koppeltaal.nl/fhir/StructureDefinition/correlation-id", new IdType(dto.getCorrelationId()));

		if (StringUtils.isNotEmpty(dto.getOutcome())) {
			auditEvent.setOutcome(AuditEvent.AuditEventOutcome.fromCode(dto.getOutcome()));
		} else {
			int x = 1;
		}
		Device device = dto.getDevice();
		if (device != null) {
			auditEvent.setAgent(singletonList(buildAgent(device)));
		} else {
			auditEvent.setAgent(singletonList(buildAgent(self)));
		}
    setMetaWithProfileUrl(auditEvent);
    return auditEvent;
	}

  @NotNull
  private Optional<Extension> getResourceOriginExtension(AuditEventDto dto) {

    if(dto.getDevice() == null) return Optional.empty();

    final Extension resourceOriginExtension = new Extension();
    resourceOriginExtension.setUrl(RESOURCE_ORIGIN_SYSTEM);
    final Reference deviceReference = new Reference(dto.getDevice());
    deviceReference.setType(ResourceType.Device.name());
    resourceOriginExtension.setValue(deviceReference);
    return Optional.of(resourceOriginExtension);
  }

  private void setMetaWithProfileUrl(AuditEvent auditEvent) {
    Meta profileMeta = new Meta();
    profileMeta.setProfile(Collections.singletonList(new CanonicalType(META_PROFILE_URL)));
    auditEvent.setMeta(profileMeta);
  }

  private AuditEvent.AuditEventAgentComponent buildAgent(Device device) {
		AuditEvent.AuditEventAgentComponent rv = new AuditEvent.AuditEventAgentComponent();
		rv.setWho(newReference(device));
		rv.setType(new CodeableConcept(CODING_APPLICATION));
		rv.setRequestor(true);
		return rv;
	}

	private AuditEvent.AuditEventEntityComponent buildAuditEventEntityComponent(Resource entity, AuditEventDto auditEvent) {
		AuditEvent.AuditEventEntityComponent component = new AuditEvent.AuditEventEntityComponent();
		component.setWhatTarget(entity);
		Reference reference = newReference(entity);
		component.setWhat(reference);
		component.setType(new Coding("http://hl7.org/fhir/resource-types", reference.getType(), reference.getDisplay()));
    String query = auditEvent.getQuery();
		if (StringUtils.isNotEmpty(query)) {
			component.setQuery(query.getBytes(StandardCharsets.UTF_8));
		} else {
		  component.setName(reference.getType()); //it's not allowed to both set the name and query http://hl7.org/fhir/R4B/auditevent-definitions.html#AuditEvent.entity.name
    }
		return component;
	}

	private AuditEvent.AuditEventSourceComponent buildEventSource() {
		return new AuditEvent.AuditEventSourceComponent()
			.setSite(fhirServerAuditLogConfiguration.getSite())
			.setObserver(newReference(self));
	}

	private void buildEventType(AuditEvent auditEvent, AuditEventDto.EventType eventType) {
		if (eventType == AuditEventDto.EventType.SendNotification) {
			auditEvent.setType(CODING_TRANSMIT);
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
		String system = fhirServerAuditLogConfiguration.getObserver().getIdentifier().getSystem();
		String value = fhirServerAuditLogConfiguration.getObserver().getIdentifier().getValue();
		Device myDevice = new Device();
		Identifier identifier = new Identifier();
		identifier.setSystem(system);
		identifier.setValue(value);
		myDevice.setIdentifier(Collections.singletonList(identifier));
		DaoMethodOutcome outcome = deviceDao.create(myDevice);
		return (Device) outcome.getResource();
	}

	private Device findObserver() {
		String system = fhirServerAuditLogConfiguration.getObserver().getIdentifier().getSystem();
		String value = fhirServerAuditLogConfiguration.getObserver().getIdentifier().getValue();
		RequestDetails requestDetails = new SystemRequestDetails();
		SearchParameterMap map = new SearchParameterMap();
		map.add("identifier", new IdentifierDt(system, value));
		IBundleProvider search = deviceDao.search(map, requestDetails);
		if (!search.isEmpty()) {
			return (Device) search.getResources(0, 1).get(0);
		}
		return null;
	}

	@NotNull
	private Reference newReference(Resource entity) {
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
