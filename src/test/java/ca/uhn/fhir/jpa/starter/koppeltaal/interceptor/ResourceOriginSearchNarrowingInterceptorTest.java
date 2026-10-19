package ca.uhn.fhir.jpa.starter.koppeltaal.interceptor;

import ca.uhn.fhir.jpa.api.config.JpaStorageSettings;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.starter.koppeltaal.util.ResourceOriginUtil;
import ca.uhn.fhir.rest.api.RequestTypeEnum;
import ca.uhn.fhir.rest.api.RestOperationTypeEnum;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r4.model.Device;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.ResourceType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;

@ExtendWith(MockitoExtension.class)
class ResourceOriginSearchNarrowingInterceptorTest extends BaseResourceOriginTest {

	private static final String OWN_DELETE_PENDING_TASK =
			"(code eq http://vzvz.nl/fhir/CodeSystem/koppeltaal-task-code|delete-pending and owner eq Device/me)";

	private ResourceOriginSearchNarrowingInterceptor interceptor;
	private final JpaStorageSettings storageSettings = new JpaStorageSettings();
	private Optional<IIdType> serverDevice = Optional.of(new IdType("Device", "server"));

	@BeforeEach
	void init(@Mock DaoRegistry daoRegistry, @Mock IFhirResourceDao<Device> deviceDao) {

    this.daoRegistry = daoRegistry;
		storageSettings.setFilterParameterEnabled(true);
		interceptor = new ResourceOriginSearchNarrowingInterceptor(daoRegistry, deviceDao, storageSettings,
				() -> serverDevice);

		final Device device = new Device();
		device.setIdElement(new IdType("Device", 123L));

		resourceOriginUtil.when(() -> ResourceOriginUtil.getDevice(any(RequestDetails.class), any()))
			.thenReturn(Optional.of(device));
		resourceOriginUtil.when(() -> ResourceOriginUtil.getRequesterClientId(any(RequestDetails.class)))
			.thenReturn(Optional.of("me"));
	}

	@Test
	void aGrantedTaskSearchAlsoMatchesTheCallersOwnDeletePendingTask() {

		RequestDetails requestDetails = search(ResourceType.Task, "Device/2,Device/1");

		interceptor.narrowSearch(requestDetails);

		assertArrayEquals(new String[] {"resource-origin eq Device/1 or resource-origin eq Device/2 or "
				+ OWN_DELETE_PENDING_TASK}, requestDetails.getParameters().get("_filter"));
		assertNull(requestDetails.getParameters().get("resource-origin"), "The origins moved into the filter");
	}

	@Test
	void anApplicationFilterOnTaskIsIntersectedWithTheNarrowing() {

		RequestDetails requestDetails = search(ResourceType.Task, "Device/1");
		requestDetails.setParameters(Map.of("_filter", new String[] {"status eq requested"}));

		interceptor.narrowSearch(requestDetails);

		assertArrayEquals(new String[] {"(status eq requested) and (resource-origin eq Device/1 or "
				+ OWN_DELETE_PENDING_TASK + ")"}, requestDetails.getParameters().get("_filter"));
	}

	@Test
	void anApplicationFilterThatClosesTheGroupEarlyIsRefused() {

		RequestDetails requestDetails = search(ResourceType.Task, "Device/1");
		requestDetails.setParameters(Map.of("_filter", new String[] {"status eq requested) or (status eq ready"}));

		assertThrows(InvalidRequestException.class, () -> interceptor.narrowSearch(requestDetails));
	}

	@Test
	void withoutFilterSupportATaskSearchKeepsToTheOrigins() {

		storageSettings.setFilterParameterEnabled(false);
		RequestDetails requestDetails = search(ResourceType.Task, "Device/1");

		interceptor.narrowSearch(requestDetails);

		assertArrayEquals(new String[] {"Device/1"}, requestDetails.getParameters().get("resource-origin"));
		assertNull(requestDetails.getParameters().get("_filter"));
	}

	@Test
	void withoutAKnownCallerATaskSearchKeepsToTheOrigins() {

		resourceOriginUtil.when(() -> ResourceOriginUtil.getRequesterClientId(any(RequestDetails.class)))
			.thenReturn(Optional.empty());
		RequestDetails requestDetails = search(ResourceType.Task, "Device/1");

		interceptor.narrowSearch(requestDetails);

		assertArrayEquals(new String[] {"Device/1"}, requestDetails.getParameters().get("resource-origin"));
		assertNull(requestDetails.getParameters().get("_filter"));
	}

	@Test
	void aGrantedAuditEventSearchAlsoMatchesTheDeleteFlowEventsOfTheServer() {

		RequestDetails requestDetails = search(ResourceType.AuditEvent, "Device/1");

		interceptor.narrowSearch(requestDetails);

		assertArrayEquals(new String[] {"Device/1,Device/server"}, requestDetails.getParameters().get("resource-origin"));
	}

	@Test
	void withoutAKnownServerDeviceAnAuditEventSearchKeepsToTheOrigins() {

		serverDevice = Optional.empty();
		RequestDetails requestDetails = search(ResourceType.AuditEvent, "Device/1");

		interceptor.narrowSearch(requestDetails);

		assertArrayEquals(new String[] {"Device/1"}, requestDetails.getParameters().get("resource-origin"));
	}

	private RequestDetails search(ResourceType resourceType, String resourceOrigins) {
		RequestDetails requestDetails = getRequestDetailsAndConfigurePermission(RequestTypeEnum.GET, resourceType, null, "r",
				resourceOrigins);
		requestDetails.setRestOperationType(RestOperationTypeEnum.SEARCH_TYPE);
		return requestDetails;
	}

	@Test
	public void shouldNarrowOwnPermission() {

    RequestDetails requestDetails = getRequestDetailsAndConfigurePermission(RequestTypeEnum.GET, ResourceType.Task, null, "r", "Device/123");

		interceptor.narrowSearch(requestDetails);

		final Map<String, String[]> parameters = requestDetails.getParameters();
		final String[] resourceOrigins = parameters.get("resource-origin");
		assertEquals(1, resourceOrigins.length,  "Should only contain one narrowed Device");
		assertEquals("Device/123", resourceOrigins[0], "Should narrow to OWN");
	}

	@Test
	public void shouldNarrowGrantedPermission() {

    RequestDetails requestDetails = getRequestDetailsAndConfigurePermission(RequestTypeEnum.GET, ResourceType.Task, null, "r", "Device/2,Device/1");

    interceptor.narrowSearch(requestDetails);

		final Map<String, String[]> parameters = requestDetails.getParameters();
		final String[] resourceOrigins = parameters.get("resource-origin");
		assertEquals(1, resourceOrigins.length,  "Should contain two granted Devices");
		assertEquals("Device/2,Device/1", resourceOrigins[0], "Expected to narrow Device/1,Device/2");
	}

	@Test
	public void shouldNotNarrow() {

    RequestDetails requestDetails = getRequestDetailsAndConfigurePermission(RequestTypeEnum.GET, ResourceType.Task, null, "r");

		interceptor.narrowSearch(requestDetails);

		final Map<String, String[]> parameters = requestDetails.getParameters();
		final String[] resourceOrigins = parameters.get("resource-origin");
		assertNull(resourceOrigins,  "Should not narrow an ALL permission");
	}

}
