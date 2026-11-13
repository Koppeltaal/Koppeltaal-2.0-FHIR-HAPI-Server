package ca.uhn.fhir.jpa.starter.koppeltaal.deactivation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.starter.koppeltaal.interceptor.JwtSecurityInterceptor;
import ca.uhn.fhir.jpa.starter.koppeltaal.deactivation.ClientDeactivationProperties.Mode;
import ca.uhn.fhir.rest.api.RequestTypeEnum;
import ca.uhn.fhir.rest.api.RestOperationTypeEnum;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import ca.uhn.fhir.rest.server.exceptions.ForbiddenOperationException;
import ca.uhn.fhir.rest.server.exceptions.InternalErrorException;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import ca.uhn.fhir.rest.server.exceptions.ResourceVersionConflictException;
import ca.uhn.fhir.rest.server.exceptions.UnprocessableEntityException;
import ca.uhn.fhir.rest.server.servlet.ServletRequestDetails;
import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r4.model.Device;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;

class ClientDeactivationInterceptorTest {

	private static final String CLIENT = "client-a";
	private static final String DOMAIN_ADMIN = "domain-admin";
	private static final byte[] BODY = "{\"resourceType\":\"AuditEvent\"}".getBytes(StandardCharsets.UTF_8);

	private final MutableClock clock = new MutableClock(Instant.parse("2026-11-11T10:00:00Z"));
	private final ClientDeactivationProperties properties = new ClientDeactivationProperties();
	private final DomainAdminDeactivationClient domainAdmin = mock(DomainAdminDeactivationClient.class);
	private final DaoRegistry daoRegistry = mock(DaoRegistry.class);
	@SuppressWarnings("unchecked")
	private final IFhirResourceDao<Device> deviceDao = mock(IFhirResourceDao.class);
	private ClientDeactivationInterceptor interceptor;

	@BeforeEach
	void setUp() {
		when(daoRegistry.getResourceDao(Device.class)).thenReturn(deviceDao);
		deviceStatus(Device.FHIRDeviceStatus.ACTIVE);
		interceptor = interceptor(Mode.REPORT);
	}

	private ClientDeactivationInterceptor interceptor(Mode mode) {
		properties.setMode(mode);
		// detects after 3 rejections of one request
		RepeatedRejectionDetector detector = new RepeatedRejectionDetector(Duration.ofMinutes(10), 3, 3, 10, clock);
		return new ClientDeactivationInterceptor(properties, detector, domainAdmin, daoRegistry, DOMAIN_ADMIN, clock);
	}

	private static Device device(Device.FHIRDeviceStatus status) {
		Device device = new Device();
		device.setStatus(status);
		return device;
	}

	/** doReturn/doThrow: re-stubbing with when() would call the mock and fire an earlier thenThrow. */
	private void deviceStatus(Device.FHIRDeviceStatus status) {
		doReturn(device(status)).when(deviceDao).read(any(IIdType.class), any(RequestDetails.class));
	}

	private void deviceReadFails(RuntimeException failure) {
		doThrow(failure).when(deviceDao).read(any(IIdType.class), any(RequestDetails.class));
	}

	private static MockHttpServletRequest httpRequest(String clientId) {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/fhir/DEFAULT/AuditEvent");
		String token = JWT.create().withClaim("azp", clientId).sign(Algorithm.HMAC256("super-secret"));
		request.setAttribute(JwtSecurityInterceptor.VALIDATED_ACCESS_TOKEN_ATTRIBUTE, token);
		return request;
	}

	private static ServletRequestDetails write(String clientId, RequestTypeEnum method, RestOperationTypeEnum operation,
			String path) {
		ServletRequestDetails requestDetails = new ServletRequestDetails();
		requestDetails.setServletRequest(httpRequest(clientId));
		requestDetails.setRequestType(method);
		requestDetails.setRestOperationType(operation);
		requestDetails.setRequestPath(path);
		requestDetails.setRequestContents(BODY);
		return requestDetails;
	}

	private void rejectCreates(int times, String clientId, Throwable rejection) {
		for (int i = 0; i < times; i++) {
			interceptor.countRejectedWrite(write(clientId, RequestTypeEnum.POST, RestOperationTypeEnum.CREATE, "AuditEvent"),
					rejection);
		}
	}

	private static InvalidRequestException deviceNotFound() {
		return new InvalidRequestException("HAPI-1094: Resource Device/x not found, specified in path: AuditEvent.agent.who");
	}

	private Runnable reportedConfirmation() {
		ArgumentCaptor<Runnable> onConfirmed = ArgumentCaptor.forClass(Runnable.class);
		verify(domainAdmin).report(eq(CLIENT), eq(DomainAdminDeactivationClient.Mode.ENFORCE), anyString(),
				contains("3 geweigerde schrijfverzoeken"), onConfirmed.capture());
		return onConfirmed.getValue();
	}

	@Test
	void reportsARepeatedRejectedCreateWithoutBlocking() {
		rejectCreates(3, CLIENT, deviceNotFound());

		verify(domainAdmin).report(eq(CLIENT), eq(DomainAdminDeactivationClient.Mode.REPORT), anyString(),
				contains("3 geweigerde schrijfverzoeken"), any());
		assertThatCode(() -> interceptor.refuseInactiveClient(httpRequest(CLIENT))).doesNotThrowAnyException();
	}

	@Test
	void countsUnprocessableUpdatesToo() {
		for (int i = 0; i < 3; i++) {
			interceptor.countRejectedWrite(write(CLIENT, RequestTypeEnum.PUT, RestOperationTypeEnum.UPDATE, "Task/1"),
					new UnprocessableEntityException("invalid"));
		}

		verify(domainAdmin).report(eq(CLIENT), eq(DomainAdminDeactivationClient.Mode.REPORT), anyString(), any(), any());
	}

	@Test
	void ignoresOtherFailuresAndOperations() {
		rejectCreates(5, CLIENT, new ForbiddenOperationException("no"));
		rejectCreates(5, CLIENT, new ResourceVersionConflictException("conflict"));
		rejectCreates(5, CLIENT, new InternalErrorException("boom"));
		rejectCreates(5, CLIENT, new IllegalStateException("not a server response"));
		for (int i = 0; i < 5; i++) {
			interceptor.countRejectedWrite(write(CLIENT, RequestTypeEnum.GET, RestOperationTypeEnum.SEARCH_TYPE, "Task"),
					deviceNotFound());
			interceptor.countRejectedWrite(write(CLIENT, RequestTypeEnum.POST, RestOperationTypeEnum.TRANSACTION, ""),
					deviceNotFound());
		}

		verifyNoInteractions(domainAdmin);
	}

	@Test
	void neverCountsTheDomainAdminClient() {
		rejectCreates(10, DOMAIN_ADMIN, deviceNotFound());

		verifyNoInteractions(domainAdmin);
	}

	@Test
	void reportsAtMostOncePerInterval() {
		rejectCreates(9, CLIENT, deviceNotFound());
		clock.advance(Duration.ofMinutes(59));
		rejectCreates(3, CLIENT, deviceNotFound());
		verify(domainAdmin, times(1)).report(eq(CLIENT), eq(DomainAdminDeactivationClient.Mode.REPORT), anyString(), any(), any());

		clock.advance(Duration.ofMinutes(1));
		rejectCreates(3, CLIENT, deviceNotFound());
		verify(domainAdmin, times(2)).report(eq(CLIENT), eq(DomainAdminDeactivationClient.Mode.REPORT), anyString(), any(), any());
	}

	@Test
	void enforceBlocksLocallyUntilDomainAdminConfirms() {
		interceptor = interceptor(Mode.ENFORCE);

		rejectCreates(3, CLIENT, deviceNotFound());
		Runnable onConfirmed = reportedConfirmation();

		assertThatThrownBy(() -> interceptor.refuseInactiveClient(httpRequest(CLIENT)))
				.isInstanceOf(InactiveClientException.class)
				.hasMessageContaining("Client is not active");
		assertThatCode(() -> interceptor.refuseInactiveClient(httpRequest("client-b"))).doesNotThrowAnyException();

		onConfirmed.run();

		assertThatCode(() -> interceptor.refuseInactiveClient(httpRequest(CLIENT))).doesNotThrowAnyException();
	}

	@Test
	void enforceAsksDomainAdminOnce() {
		interceptor = interceptor(Mode.ENFORCE);

		rejectCreates(6, CLIENT, deviceNotFound());

		verify(domainAdmin, times(1)).report(eq(CLIENT), eq(DomainAdminDeactivationClient.Mode.ENFORCE), anyString(), any(), any());
	}

	@Test
	void aStatusCachedBeforeTheConfirmationDoesNotCount() {
		interceptor = interceptor(Mode.ENFORCE);
		interceptor.refuseInactiveClient(httpRequest(CLIENT)); // caches the active Device
		rejectCreates(3, CLIENT, deviceNotFound());
		Runnable onConfirmed = reportedConfirmation();
		deviceStatus(Device.FHIRDeviceStatus.INACTIVE); // domain-admin deactivated it before confirming

		onConfirmed.run();

		assertThatThrownBy(() -> interceptor.refuseInactiveClient(httpRequest(CLIENT)))
				.isInstanceOf(InactiveClientException.class);
	}

	@Test
	void aDeviceReadInFlightDuringTheConfirmationDoesNotCountAfterwards() throws Exception {
		interceptor = interceptor(Mode.ENFORCE);
		CountDownLatch readStarted = new CountDownLatch(1);
		CountDownLatch confirmed = new CountDownLatch(1);
		doAnswer(invocation -> {
			readStarted.countDown();
			confirmed.await(5, TimeUnit.SECONDS);
			return device(Device.FHIRDeviceStatus.ACTIVE); // read before domain-admin deactivated the Device
		}).doReturn(device(Device.FHIRDeviceStatus.INACTIVE)).when(deviceDao).read(any(IIdType.class), any(RequestDetails.class));

		ExecutorService thread = Executors.newSingleThreadExecutor();
		try {
			Future<?> inFlight = thread.submit(() -> interceptor.refuseInactiveClient(httpRequest(CLIENT)));
			assertThat(readStarted.await(5, TimeUnit.SECONDS)).isTrue();
			rejectCreates(3, CLIENT, deviceNotFound());
			reportedConfirmation().run();
			confirmed.countDown();
			inFlight.get(5, TimeUnit.SECONDS);
		} finally {
			confirmed.countDown();
			thread.shutdownNow();
		}

		assertThatThrownBy(() -> interceptor.refuseInactiveClient(httpRequest(CLIENT)))
				.isInstanceOf(InactiveClientException.class);
	}

	@Test
	void refusesAClientWhoseDeviceIsInactiveAndCachesTheStatus() {
		deviceStatus(Device.FHIRDeviceStatus.INACTIVE);

		assertThatThrownBy(() -> interceptor.refuseInactiveClient(httpRequest(CLIENT))).isInstanceOf(InactiveClientException.class);
		assertThatThrownBy(() -> interceptor.refuseInactiveClient(httpRequest(CLIENT))).isInstanceOf(InactiveClientException.class);
		verify(deviceDao, times(1)).read(argThat(id -> "Device/client-a".equals(id.getValue())), any(SystemRequestDetails.class));

		deviceStatus(Device.FHIRDeviceStatus.ACTIVE);
		clock.advance(Duration.ofSeconds(30));

		assertThatCode(() -> interceptor.refuseInactiveClient(httpRequest(CLIENT))).doesNotThrowAnyException();
		verify(deviceDao, times(2)).read(any(IIdType.class), any(RequestDetails.class));
	}

	@Test
	void aMissingOrUnreadableDeviceIsNoReasonToRefuse() {
		deviceReadFails(new ResourceNotFoundException("Device/client-a"));
		assertThatCode(() -> interceptor.refuseInactiveClient(httpRequest(CLIENT))).doesNotThrowAnyException();

		clock.advance(Duration.ofSeconds(30));
		deviceReadFails(new InternalErrorException("database down"));
		assertThatCode(() -> interceptor.refuseInactiveClient(httpRequest(CLIENT))).doesNotThrowAnyException();
		verify(deviceDao, times(2)).read(any(IIdType.class), any(RequestDetails.class));
	}

	@Test
	void neverRefusesTheDomainAdminClient() {
		deviceStatus(Device.FHIRDeviceStatus.INACTIVE);

		assertThatCode(() -> interceptor.refuseInactiveClient(httpRequest(DOMAIN_ADMIN))).doesNotThrowAnyException();
		verifyNoInteractions(deviceDao);
	}

	@Test
	void leavesRequestsWithoutAValidatedTokenAlone() {
		assertThatCode(() -> interceptor.refuseInactiveClient(new MockHttpServletRequest("GET", "/fhir/DEFAULT/metadata")))
				.doesNotThrowAnyException();
		verifyNoInteractions(deviceDao);
	}

	@Test
	void offDoesNothing() {
		interceptor = interceptor(Mode.OFF);
		deviceStatus(Device.FHIRDeviceStatus.INACTIVE);

		rejectCreates(10, CLIENT, deviceNotFound());

		assertThatCode(() -> interceptor.refuseInactiveClient(httpRequest(CLIENT))).doesNotThrowAnyException();
		verifyNoInteractions(domainAdmin, deviceDao);
	}

	@Test
	void countingNeverThrows() {
		doThrow(new IllegalStateException("executor gone")).when(domainAdmin).report(any(), any(), any(), any(), any());

		assertThatCode(() -> rejectCreates(3, CLIENT, deviceNotFound())).doesNotThrowAnyException();
	}
}
