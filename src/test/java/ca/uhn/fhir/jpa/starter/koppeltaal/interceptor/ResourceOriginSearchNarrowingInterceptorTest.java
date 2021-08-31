package ca.uhn.fhir.jpa.starter.koppeltaal.interceptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.starter.koppeltaal.dto.CrudOperation;
import ca.uhn.fhir.jpa.starter.koppeltaal.dto.PermissionDto;
import ca.uhn.fhir.jpa.starter.koppeltaal.dto.PermissionScope;
import ca.uhn.fhir.jpa.starter.koppeltaal.service.SmartBackendServiceAuthorizationService;
import ca.uhn.fhir.jpa.starter.koppeltaal.util.ResourceOriginUtil;
import ca.uhn.fhir.rest.api.RequestTypeEnum;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.servlet.ServletRequestDetails;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.hl7.fhir.r4.model.Device;
import org.hl7.fhir.r4.model.IdType;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ResourceOriginSearchNarrowingInterceptorTest {

	private static MockedStatic<ResourceOriginUtil> resourceOriginUtil;

	private ResourceOriginSearchNarrowingInterceptor interceptor;
	private SmartBackendServiceAuthorizationService smartBackendServiceAuthorizationService;

	@BeforeAll
	public static void initAll() {
		resourceOriginUtil = mockStatic(ResourceOriginUtil.class);
	}

	@AfterAll
	public static void afterAll() {
		resourceOriginUtil.close();
	}

	@BeforeEach
	void init(@Mock DaoRegistry daoRegistry, @Mock SmartBackendServiceAuthorizationService smartBackendServiceAuthorizationService) {

		this.smartBackendServiceAuthorizationService = smartBackendServiceAuthorizationService;

		interceptor = new ResourceOriginSearchNarrowingInterceptor(
			daoRegistry,
			smartBackendServiceAuthorizationService
		);

		final Device device = new Device();
		device.setIdElement(new IdType("Device", 123L));

		resourceOriginUtil.when(() -> ResourceOriginUtil.getDevice(any(RequestDetails.class), any()))
			.thenReturn(Optional.of(device));
	}

	@Test
	public void shouldNarrowOwnPermission() {

		RequestDetails requestDetails = getRequestDetailsAndConfigurePermission(PermissionScope.OWN);

		interceptor.narrowSearch(requestDetails);

		final Map<String, String[]> parameters = requestDetails.getParameters();
		final String[] resourceOrigins = parameters.get("resource-origin");
		assertEquals(1, resourceOrigins.length,  "Should only contain one narrowed Device");
		assertEquals("Device/123", resourceOrigins[0], "Should narrow to OWN");
	}

	@Test
	public void shouldNarrowGrantedPermission() {

		RequestDetails requestDetails = getRequestDetailsAndConfigurePermission(PermissionScope.GRANTED);

		interceptor.narrowSearch(requestDetails);

		final Map<String, String[]> parameters = requestDetails.getParameters();
		final String[] resourceOrigins = parameters.get("resource-origin");
		assertEquals(1, resourceOrigins.length,  "Should contain two granted Devices");
		assertEquals("Device/1,Device/2", resourceOrigins[0], "Expected to narrow Device/1,Device/2");
	}

	@Test
	public void shouldNotNarrow() {

		RequestDetails requestDetails = getRequestDetailsAndConfigurePermission(PermissionScope.ALL);

		interceptor.narrowSearch(requestDetails);

		final Map<String, String[]> parameters = requestDetails.getParameters();
		final String[] resourceOrigins = parameters.get("resource-origin");
		assertNull(resourceOrigins,  "Should not narrow an ALL permission");
	}

	@NotNull
	private RequestDetails getRequestDetailsAndConfigurePermission(PermissionScope scope) {
		RequestDetails requestDetails = new ServletRequestDetails();
		requestDetails.setResourceName("Task");
		requestDetails.setRequestType(RequestTypeEnum.GET);

		final PermissionDto permission = new PermissionDto();
		permission.setScope(scope);
		permission.setOperation(CrudOperation.READ);

		//Grant devices to try and trick the system when the permission isn't GRANTED, should be ignored.
		final Set<String> grantedDeviceIds = new TreeSet<>();
		grantedDeviceIds.add("1");
		grantedDeviceIds.add("2");
		permission.setGrantedDeviceIds(grantedDeviceIds);

		when(smartBackendServiceAuthorizationService.getPermission(anyString(), eq(requestDetails)))
			.thenReturn(Optional.of(permission));
		return requestDetails;
	}
}