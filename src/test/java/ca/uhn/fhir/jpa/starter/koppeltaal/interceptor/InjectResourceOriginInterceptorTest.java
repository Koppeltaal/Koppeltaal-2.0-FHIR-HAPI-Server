package ca.uhn.fhir.jpa.starter.koppeltaal.interceptor;

import static ca.uhn.fhir.jpa.starter.koppeltaal.util.ResourceOriginUtil.RESOURCE_ORIGIN_SYSTEM;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.starter.koppeltaal.config.SmartBackendServiceConfiguration;
import ca.uhn.fhir.jpa.starter.koppeltaal.util.ResourceOriginUtil;
import ca.uhn.fhir.rest.api.RestOperationTypeEnum;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.exceptions.ForbiddenOperationException;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import ca.uhn.fhir.rest.server.servlet.ServletRequestDetails;
import java.util.List;
import java.util.Optional;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r4.model.Device;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.ResourceType;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class InjectResourceOriginInterceptorTest {

	private static MockedStatic<ResourceOriginUtil> resourceOriginUtil;

	private DaoRegistry daoRegistry;
	private InjectResourceOriginInterceptor interceptor;

	@BeforeAll
	public static void initAll() {
		resourceOriginUtil = mockStatic(ResourceOriginUtil.class);
	}

	@AfterAll
	public static void afterAll() {
		resourceOriginUtil.close();
	}

	@BeforeEach
	void init(
		@Mock(answer = Answers.RETURNS_DEEP_STUBS) DaoRegistry daoRegistry,
		@Mock IFhirResourceDao<Device> deviceDao,
		@Mock SmartBackendServiceConfiguration smartBackendServiceConfiguration
	) {

		this.daoRegistry = daoRegistry;

		interceptor = new InjectResourceOriginInterceptor(
			daoRegistry,
			deviceDao,
			smartBackendServiceConfiguration
		);

		final Device device = new Device();
		device.setIdElement(new IdType("Device", 123L));

		resourceOriginUtil.when(() -> ResourceOriginUtil.getDevice(any(RequestDetails.class), any()))
			.thenReturn(Optional.of(device));
	}

	@Test
	public void shouldSetResourceOriginOnCreate() {

		final ServletRequestDetails requestDetails = new ServletRequestDetails();
		requestDetails.setRestOperationType(RestOperationTypeEnum.CREATE);
		final Patient resource = new Patient();
		requestDetails.setResource(resource);

		interceptor.incomingRequestPreHandled(requestDetails);

		final List<Extension> resourceOriginExtension = resource.getExtensionsByUrl(RESOURCE_ORIGIN_SYSTEM);
		final String referenceValue = ((Reference) resourceOriginExtension.get(0).getValue()).getResource().getIdElement().getValue();

		assertEquals(1, resourceOriginExtension.size());
		assertEquals("Device/123", referenceValue);
	}

	@Test
	public void shouldForceResourceOriginSetOnUpdate() {
		final ServletRequestDetails requestDetails = new ServletRequestDetails();
		requestDetails.setRestOperationType(RestOperationTypeEnum.UPDATE);
		final Patient resource = new Patient();
		requestDetails.setResource(resource);

		assertThrows(InvalidRequestException.class, ()  ->
			interceptor.incomingRequestPreHandled(requestDetails)
		);
	}

	@Test
	public void shouldForceResourceOriginSetOnDelete() {
		final ServletRequestDetails requestDetails = new ServletRequestDetails();
		requestDetails.setRestOperationType(RestOperationTypeEnum.DELETE);
		final Patient resource = new Patient();
		requestDetails.setResource(resource);

		assertThrows(InvalidRequestException.class, ()  ->
			interceptor.incomingRequestPreHandled(requestDetails)
		);
	}

	@Test
	public void shouldNotBeAbleToChangeResourceOrigin() {

		final ServletRequestDetails requestDetails = new ServletRequestDetails();
		requestDetails.setRestOperationType(RestOperationTypeEnum.UPDATE);
		requestDetails.setResourceName("Patient");
		requestDetails.setId(new IdType("Patient/5"));
		setPatientWithResourceOriginAsResource(requestDetails, "Device/567");

		final Patient resourceFromDatabase = createPatientWithResourceOrigin("Device/123");
		IFhirResourceDao<Patient> resourceDao = mock(IFhirResourceDao.class);

		when(daoRegistry.getResourceDao(anyString()))
			.thenReturn(resourceDao);

		when(resourceDao.read(any(IIdType.class)))
			.thenReturn(resourceFromDatabase);

		assertThrows(ForbiddenOperationException.class, () ->
			interceptor.incomingRequestPreHandled(requestDetails)
		);
	}

	@Test
	public void shouldRejectProvidedResourceOriginOnCreates() {
		final ServletRequestDetails requestDetails = new ServletRequestDetails();
		requestDetails.setRestOperationType(RestOperationTypeEnum.CREATE);
		setPatientWithResourceOriginAsResource(requestDetails, "Device/123");

		assertThrows(InvalidRequestException.class, ()  ->
			interceptor.incomingRequestPreHandled(requestDetails)
		);

	}

	@Test
	public void shouldRejectNonDeviceExtensionValue() {
		final ServletRequestDetails requestDetails = new ServletRequestDetails();
		requestDetails.setRestOperationType(RestOperationTypeEnum.UPDATE);
		setPatientWithResourceOriginAsResource(requestDetails, "Patient/123");

		assertThrows(InvalidRequestException.class, () ->
			interceptor.incomingRequestPreHandled(requestDetails)
		);
	}

	@Test
	public void shouldRejectIfDeviceNotFound() {
		final ServletRequestDetails requestDetails = new ServletRequestDetails();
		requestDetails.setRestOperationType(RestOperationTypeEnum.CREATE);
		final Patient resource = new Patient();
		requestDetails.setResource(resource);

		resourceOriginUtil.when(() -> ResourceOriginUtil.getDevice(any(RequestDetails.class), any()))
			.thenReturn(Optional.empty());

		assertThrows(InvalidRequestException.class, () ->
			interceptor.incomingRequestPreHandled(requestDetails)
		);
	}

	private void setPatientWithResourceOriginAsResource(ServletRequestDetails requestDetails, String  deviceId) {
		final Patient resourceFromClient = createPatientWithResourceOrigin(deviceId);

		requestDetails.setResource(resourceFromClient);
	}

	@NotNull
	private Patient createPatientWithResourceOrigin(String deviceId) {
		final Patient resourceFromClient = new Patient();

		final Extension resourceOriginExtension = new Extension();
		resourceOriginExtension.setUrl(RESOURCE_ORIGIN_SYSTEM);
		final Reference deviceReference = new Reference(deviceId);
		deviceReference.setType(ResourceType.Device.name());
		resourceOriginExtension.setValue(deviceReference);

		resourceFromClient.addExtension(resourceOriginExtension);
		return resourceFromClient;
	}
}
