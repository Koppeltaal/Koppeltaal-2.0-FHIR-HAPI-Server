package ca.uhn.fhir.jpa.starter.koppeltaal.cleanup;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import ca.uhn.fhir.rest.param.TokenParam;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r4.model.Device;

/**
 * Determines the fan-out for announcement tasks: every active Device whose id
 * equals its koppeltaal-client-id identifier (excludes pre-unification legacy
 * registrations), plus the server's own Device (the auditlog observer) used as
 * Task.requester and AuditEvent agent.
 */
public class ParticipatingApps {

	public static final String CLIENT_ID_SYSTEM = "http://vzvz.nl/fhir/NamingSystem/koppeltaal-client-id";

	/** Fan-out ceiling; the sync search silently caps without an explicit count, and a
	 * truncated fan-out would silently skip announcements - fail loudly instead. */
	private static final int MAX_DEVICES = 2_000;

	private final DaoRegistry daoRegistry;
	private final String observerIdentifierSystem;
	private final String observerIdentifierValue;

	public ParticipatingApps(DaoRegistry daoRegistry, String observerIdentifierSystem, String observerIdentifierValue) {
		this.daoRegistry = daoRegistry;
		this.observerIdentifierSystem = observerIdentifierSystem;
		this.observerIdentifierValue = observerIdentifierValue;
	}

	public List<IIdType> participatingDeviceIds() {
		IFhirResourceDao<Device> deviceDao = daoRegistry.getResourceDao(Device.class);
		SearchParameterMap query = SearchParameterMap.newSynchronous()
				.add(Device.SP_STATUS, new TokenParam(Device.FHIRDeviceStatus.ACTIVE.toCode()));
		query.setCount(MAX_DEVICES);
		List<IBaseResource> devices = deviceDao.search(query, new SystemRequestDetails())
				.getResources(0, MAX_DEVICES);
		if (devices.size() >= MAX_DEVICES) {
			throw new IllegalStateException(String.format(
					"Active device count reached the %d cap; raise MAX_DEVICES before running", MAX_DEVICES));
		}
		return devices.stream()
				.map(Device.class::cast)
				.filter(device -> Objects.equals(device.getIdElement().getIdPart(), clientIdIdentifier(device)))
				.map(device -> device.getIdElement().toUnqualifiedVersionless())
				.collect(Collectors.toList());
	}

	public IIdType serverDeviceId() {
		IFhirResourceDao<Device> deviceDao = daoRegistry.getResourceDao(Device.class);
		SearchParameterMap query = SearchParameterMap.newSynchronous()
				.add(Device.SP_IDENTIFIER, new TokenParam(observerIdentifierSystem, observerIdentifierValue));
		List<IBaseResource> found = deviceDao.search(query, new SystemRequestDetails()).getResources(0, 2);
		if (found.isEmpty()) {
			throw new IllegalStateException(String.format(
					"No server Device found for identifier %s|%s; cannot set Task.requester",
					observerIdentifierSystem, observerIdentifierValue));
		}
		return found.get(0).getIdElement().toUnqualifiedVersionless();
	}

	private static String clientIdIdentifier(Device device) {
		return device.getIdentifier().stream()
				.filter(identifier -> CLIENT_ID_SYSTEM.equals(identifier.getSystem()))
				.map(identifier -> identifier.getValue())
				.findFirst().orElse(null);
	}
}
