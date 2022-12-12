package ca.uhn.fhir.jpa.starter.koppeltaal.interceptor;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.starter.koppeltaal.config.SmartBackendServiceConfiguration;
import ca.uhn.fhir.jpa.starter.koppeltaal.dto.CrudOperation;
import ca.uhn.fhir.jpa.starter.koppeltaal.dto.PermissionDto;
import ca.uhn.fhir.jpa.starter.koppeltaal.dto.PermissionScope;
import ca.uhn.fhir.jpa.starter.koppeltaal.service.SmartBackendServiceAuthorizationService;
import ca.uhn.fhir.jpa.starter.koppeltaal.util.ResourceOriginUtil;
import ca.uhn.fhir.rest.api.RequestTypeEnum;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.exceptions.ForbiddenOperationException;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import ca.uhn.fhir.rest.server.servlet.ServletRequestDetails;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r4.model.Device;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.ResourceType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ResourceOriginAuthorizationInterceptorTest {

	private static MockedStatic<ResourceOriginUtil> resourceOriginUtil;

	private ResourceOriginAuthorizationInterceptor interceptor;
	private DaoRegistry daoRegistry;
	private IFhirResourceDao<Device> deviceDao;
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
	void init(@Mock DaoRegistry daoRegistry,
		@Mock IFhirResourceDao<Device> deviceDao,
		@Mock SmartBackendServiceAuthorizationService smartBackendServiceAuthorizationService,
		@Mock SmartBackendServiceConfiguration smartBackendServiceConfiguration
	) {

		this.smartBackendServiceAuthorizationService = smartBackendServiceAuthorizationService;
		this.daoRegistry = daoRegistry;
		this.deviceDao = deviceDao;

		interceptor = new ResourceOriginAuthorizationInterceptor(
			daoRegistry,
			deviceDao,
			smartBackendServiceAuthorizationService,
			smartBackendServiceConfiguration
		);

		final Device device = new Device();
		device.setIdElement(new IdType("Device", 123L));

		lenient().when(deviceDao.read(any(IdType.class), any(RequestDetails.class)))
			.thenReturn(device);

		resourceOriginUtil.when(() -> ResourceOriginUtil.getDevice(any(RequestDetails.class), any()))
			.thenReturn(Optional.of(device));
	}

	@Test
	public void shouldBeUnauthenticatedWhenDeviceNotFound() {

		RequestDetails requestDetails = getRequestDetailsAndConfigurePermission(RequestTypeEnum.POST,
			CrudOperation.CREATE, ResourceType.Task, PermissionScope.ALL, null);

		resourceOriginUtil.when(() -> ResourceOriginUtil.getDevice(any(RequestDetails.class), any()))
			.thenReturn(Optional.empty());

		assertThrows(InvalidRequestException.class, () ->
			interceptor.authorizeRequest(requestDetails)
		);
	}

	@Test
	public void testPermissionsWithAllScope() {

		final IdType resourceId = new IdType(ResourceType.Task.name(), 12L);

		RequestDetails requestDetails = getRequestDetailsAndConfigurePermission(RequestTypeEnum.POST,
			CrudOperation.CREATE, ResourceType.Task, PermissionScope.ALL, resourceId);

		interceptor.authorizeRequest(requestDetails);

		requestDetails = getRequestDetailsAndConfigurePermission(RequestTypeEnum.GET,
			CrudOperation.READ, ResourceType.Task, PermissionScope.ALL, resourceId);

		interceptor.authorizeRequest(requestDetails);

		requestDetails = getRequestDetailsAndConfigurePermission(RequestTypeEnum.PUT,
			CrudOperation.UPDATE, ResourceType.Task, PermissionScope.ALL, resourceId);

		interceptor.authorizeRequest(requestDetails);

		requestDetails = getRequestDetailsAndConfigurePermission(RequestTypeEnum.DELETE,
			CrudOperation.DELETE, ResourceType.Task, PermissionScope.ALL, resourceId);

		interceptor.authorizeRequest(requestDetails);
	}

	@Test
	public void shouldAllowCreateOwn() {
		RequestDetails requestDetails = getRequestDetailsAndConfigurePermission(RequestTypeEnum.POST,
			CrudOperation.CREATE, ResourceType.Task, PermissionScope.OWN, null);

		interceptor.authorizeRequest(requestDetails);
	}

	@Test
	public void shouldNotAllowWithoutPermission() {

		RequestDetails requestDetails = getRequestDetailsAndConfigurePermission(RequestTypeEnum.POST,
			CrudOperation.UPDATE, ResourceType.Task, PermissionScope.OWN, null);

		when(smartBackendServiceAuthorizationService.getPermission(anyString(), eq(requestDetails)))
			.thenReturn(Optional.empty());

		assertThrows(ForbiddenOperationException.class, () ->
			interceptor.authorizeRequest(requestDetails)
		);
	}

	@Test
	public void shouldNotBeAbleToModifyOtherResourceWithOwnScope() {
		final IdType resourceId = new IdType(ResourceType.Task.name(), 12L);

		RequestDetails requestDetails = getRequestDetailsAndConfigurePermission(RequestTypeEnum.POST,
			CrudOperation.UPDATE, ResourceType.Task, PermissionScope.OWN, resourceId);

		final IdType otherDeviceId = new IdType("Device", 567L);
		resourceOriginUtil.when(() -> ResourceOriginUtil.getResourceOriginDeviceId(any(IBaseResource.class)))
			.thenReturn(Optional.of(otherDeviceId));

		final Device otherDevice = new Device();
		otherDevice.setId(otherDeviceId);

		when(deviceDao.read(eq(otherDeviceId), any(RequestDetails.class)))
			.thenReturn(otherDevice);

		assertThrows(ForbiddenOperationException.class, () ->
			interceptor.authorizeRequest(requestDetails)
		);
	}

	@Test
	public void shouldBeAbleToModifyOtherResourceWithOwnScope() {
		final IdType resourceId = new IdType(ResourceType.Task.name(), 12L);

		RequestDetails requestDetails = getRequestDetailsAndConfigurePermission(RequestTypeEnum.POST,
			CrudOperation.UPDATE, ResourceType.Task, PermissionScope.OWN, resourceId);

		final IdType otherDeviceId = new IdType("Device", 123L);
		resourceOriginUtil.when(() -> ResourceOriginUtil.getResourceOriginDeviceId(any(IBaseResource.class)))
			.thenReturn(Optional.of(otherDeviceId));

		final Device otherDevice = new Device();
		otherDevice.setId(otherDeviceId);

		when(deviceDao.read(eq(otherDeviceId), any(RequestDetails.class)))
			.thenReturn(otherDevice);

		interceptor.authorizeRequest(requestDetails);
	}

	@Test
	public void shouldNotBeAbleToModifyOtherResourceWithGrantedScope() {
		final IdType resourceId = new IdType(ResourceType.Task.name(), 12L);

		RequestDetails requestDetails = getRequestDetailsAndConfigurePermission(RequestTypeEnum.POST,
			CrudOperation.UPDATE, ResourceType.Task, PermissionScope.GRANTED, resourceId);

		assertThrows(ForbiddenOperationException.class, () ->
			interceptor.authorizeRequest(requestDetails)
		);
	}

	@Test
	public void shouldBeAbleToModifyOtherResourceWithGrantedScope() {
		final IdType resourceId = new IdType(ResourceType.Task.name(), 12L);

		final IdType resourceOriginDeviceId = new IdType("Device", 1L);
		resourceOriginUtil.when(() -> ResourceOriginUtil.getResourceOriginDeviceId(any(IBaseResource.class)))
			.thenReturn(Optional.of(resourceOriginDeviceId));

		final Device resourceOriginDevice = new Device();
		resourceOriginDevice.setId(resourceOriginDeviceId);

		when(deviceDao.read(eq(resourceOriginDeviceId), any(RequestDetails.class)))
			.thenReturn(resourceOriginDevice);

		// below grants Device/1 and Device/2
		RequestDetails requestDetails = getRequestDetailsAndConfigurePermission(RequestTypeEnum.POST,
			CrudOperation.UPDATE, ResourceType.Task, PermissionScope.GRANTED, resourceId);

		interceptor.authorizeRequest(requestDetails);
	}

	/**
	 * Created a configured {@link RequestDetails} and mocks the {@link
	 * SmartBackendServiceAuthorizationService} to return the mocked permission when requested.
	 *
	 * @param requestType
	 * @param crudOperation
	 * @param resourceType
	 * @param scope
	 * @param resourceId    the id of the resource that the request is tied to. <code>null</code> for
	 *                      an "ALL" call like GET /Patient
	 * @return The configured {@link RequestDetails}.
	 */
	private RequestDetails getRequestDetailsAndConfigurePermission(
		RequestTypeEnum requestType, CrudOperation crudOperation,
		ResourceType resourceType,
		PermissionScope scope,
		IdType resourceId
	) {

		RequestDetails requestDetails = new ServletRequestDetails();
		requestDetails.setResourceName(resourceType.name());
		requestDetails.setRequestType(requestType);
		requestDetails.setId(resourceId);

		final PermissionDto permission = new PermissionDto();
		permission.setScope(scope);
		permission.setOperation(crudOperation);
		permission.setResourceType(resourceType);

		//Grant devices to try and trick the system when the permission isn't GRANTED, should be ignored.
		final Set<String> grantedDeviceIds = new TreeSet<>();
		grantedDeviceIds.add("1");
		grantedDeviceIds.add("2");
		permission.setGrantedDeviceIds(grantedDeviceIds);

		lenient().when(
				smartBackendServiceAuthorizationService.getPermission(anyString(), eq(requestDetails)))
			.thenReturn(Optional.of(permission));


		final IFhirResourceDao resourceDaoMock = mock(IFhirResourceDao.class);
		lenient().when(daoRegistry.getResourceDao(eq(resourceType.name())))
			.thenReturn(resourceDaoMock);

		// when the action is executed on a resource, we want the dao registry to return an actual instance of that resource type
		if(resourceId != null) {
			try {
				final IBaseResource instance = (IBaseResource) Class.forName("org.hl7.fhir.r4.model." + resourceId.getResourceType())
					.getDeclaredConstructor()
					.newInstance();

				when(resourceDaoMock.read(any(IIdType.class), any(RequestDetails.class)))
					.thenReturn(instance);
			} catch (Exception e) {
				throw new RuntimeException("Failed to create an instance of org.hl7.fhir.r4.model." + resourceId.getResourceType(), e);
			}
		}

		return requestDetails;
	}

}
