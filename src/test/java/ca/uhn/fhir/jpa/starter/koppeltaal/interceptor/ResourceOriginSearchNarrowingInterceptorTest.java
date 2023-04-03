package ca.uhn.fhir.jpa.starter.koppeltaal.interceptor;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.starter.koppeltaal.util.ResourceOriginUtil;
import ca.uhn.fhir.rest.api.RequestTypeEnum;
import ca.uhn.fhir.rest.api.server.RequestDetails;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;

@ExtendWith(MockitoExtension.class)
class ResourceOriginSearchNarrowingInterceptorTest extends BaseResourceOriginTest {

	private ResourceOriginSearchNarrowingInterceptor interceptor;

	@BeforeEach
	void init(@Mock DaoRegistry daoRegistry, @Mock IFhirResourceDao<Device> deviceDao) {

    this.daoRegistry = daoRegistry;
		interceptor = new ResourceOriginSearchNarrowingInterceptor(daoRegistry, deviceDao);

		final Device device = new Device();
		device.setIdElement(new IdType("Device", 123L));

		resourceOriginUtil.when(() -> ResourceOriginUtil.getDevice(any(RequestDetails.class), any()))
			.thenReturn(Optional.of(device));
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
