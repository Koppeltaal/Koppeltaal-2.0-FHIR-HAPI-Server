package ca.uhn.fhir.jpa.starter.koppeltaal.deactivation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.starter.Application;
import ca.uhn.fhir.jpa.starter.koppeltaal.service.AuditEventService;
import ca.uhn.fhir.jpa.starter.koppeltaal.service.Oauth2AccessTokenService;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.hl7.fhir.r4.model.Device;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Only the running server shows that the refusal sits behind the token check and before the MIME check and the body,
 * and that HAPI answers it with a 403 that is not audited. AuditEventService is mocked: it writes after a fixed delay on
 * its own thread, so verifying its synchronous submit is the reliable check.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = {Application.class}, properties = {
		"spring.datasource.url=jdbc:h2:mem:dbclientdeactivation",
		"hapi.fhir.fhir_version=r4",
		"hapi.fhir.mdm_enabled=false",
		"hapi.fhir.cr_enabled=false",
		"hapi.fhir.subscription.resthook_enabled=false",
		"hapi.fhir.subscription.websocket_enabled=false",
		"spring.main.allow-bean-definition-overriding=true",
		"fhir.server.security.enabled=true"
})
class ClientDeactivationWiringTest {

	private static final String INACTIVE_CLIENT = "inactive-client-0001";
	private static final String ACTIVE_CLIENT = "active-client-0001";

	@LocalServerPort
	private int port;

	@MockBean
	private Oauth2AccessTokenService oauth2AccessTokenService;

	@MockBean
	private AuditEventService auditEventService;

	@Autowired
	private DaoRegistry daoRegistry;

	private final HttpClient http = HttpClient.newHttpClient();

	@BeforeEach
	void setUp() {
		when(oauth2AccessTokenService.validateToken(anyString())).thenReturn(true);
		storeDevice(INACTIVE_CLIENT, Device.FHIRDeviceStatus.INACTIVE);
		storeDevice(ACTIVE_CLIENT, Device.FHIRDeviceStatus.ACTIVE);
		clearInvocations(auditEventService);
	}

	private void storeDevice(String id, Device.FHIRDeviceStatus status) {
		Device device = new Device();
		device.setId(id);
		device.setStatus(status);
		daoRegistry.getResourceDao(Device.class).update(device, new SystemRequestDetails());
	}

	private URI patients() {
		return URI.create("http://localhost:" + port + "/fhir/DEFAULT/Patient");
	}

	private HttpRequest.Builder create(String contentType, String body) {
		return HttpRequest.newBuilder(patients())
				.header("Content-Type", contentType)
				.POST(HttpRequest.BodyPublishers.ofString(body));
	}

	private HttpResponse<String> send(String clientId, HttpRequest.Builder request) throws Exception {
		String token = JWT.create().withClaim("azp", clientId).withClaim("scope", "system/*.cruds")
				.sign(Algorithm.HMAC256("test"));
		return http.send(request.header("Authorization", "Bearer " + token).build(), HttpResponse.BodyHandlers.ofString());
	}

	@Test
	void refusesAnInactiveClientBeforeTheMediaTypeAndTheBodyAreCheckedWithoutAuditing() throws Exception {
		HttpResponse<String> read = send(INACTIVE_CLIENT, HttpRequest.newBuilder(patients()).GET());
		HttpResponse<String> invalidJson = send(INACTIVE_CLIENT, create("application/fhir+json", "{ not json"));
		HttpResponse<String> unsupportedType = send(INACTIVE_CLIENT, create("text/plain", "hello"));

		assertThat(read.statusCode()).isEqualTo(403);
		assertThat(read.body()).contains("Client is not active");
		assertThat(invalidJson.statusCode()).isEqualTo(403);
		assertThat(unsupportedType.statusCode()).isEqualTo(403);
		verify(auditEventService, never()).submitAuditEvent(any(), any());
	}

	@Test
	void stillChecksAndAuditsTheRequestsOfAnActiveClient() throws Exception {
		HttpResponse<String> invalidJson = send(ACTIVE_CLIENT, create("application/fhir+json", "{ not json"));
		HttpResponse<String> unsupportedType = send(ACTIVE_CLIENT, create("text/plain", "hello"));

		assertThat(invalidJson.statusCode()).isEqualTo(400);
		assertThat(invalidJson.body()).contains("Invalid JSON syntax");
		assertThat(unsupportedType.statusCode()).isEqualTo(415);
		verify(auditEventService, atLeastOnce()).submitAuditEvent(any(), any());
	}
}
