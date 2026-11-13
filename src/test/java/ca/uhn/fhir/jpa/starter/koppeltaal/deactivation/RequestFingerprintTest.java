package ca.uhn.fhir.jpa.starter.koppeltaal.deactivation;

import static org.assertj.core.api.Assertions.assertThat;

import ca.uhn.fhir.rest.api.RequestTypeEnum;
import ca.uhn.fhir.rest.server.servlet.ServletRequestDetails;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class RequestFingerprintTest {

	private static final byte[] BODY = "{\"resourceType\":\"AuditEvent\"}".getBytes(StandardCharsets.UTF_8);

	@Test
	void equalRequestsHaveEqualFingerprints() {
		assertThat(RequestFingerprint.of("POST", "AuditEvent", BODY))
				.isEqualTo(RequestFingerprint.of("POST", "AuditEvent", BODY.clone()))
				.hasSize(64);
	}

	@Test
	void aDifferentBodyPathOrMethodChangesTheFingerprint() {
		String fingerprint = RequestFingerprint.of("PUT", "Task/1", BODY);

		assertThat(RequestFingerprint.of("PUT", "Task/1", "{}".getBytes(StandardCharsets.UTF_8))).isNotEqualTo(fingerprint);
		assertThat(RequestFingerprint.of("PUT", "Task/2", BODY)).isNotEqualTo(fingerprint);
		assertThat(RequestFingerprint.of("POST", "Task/1", BODY)).isNotEqualTo(fingerprint);
	}

	@Test
	void aMissingBodyCountsAsEmpty() {
		assertThat(RequestFingerprint.of("POST", "AuditEvent", null))
				.isEqualTo(RequestFingerprint.of("POST", "AuditEvent", new byte[0]));
	}

	@Test
	void readsMethodPathAndTheLoadedBodyFromTheRequest() {
		ServletRequestDetails requestDetails = new ServletRequestDetails();
		requestDetails.setRequestType(RequestTypeEnum.POST);
		requestDetails.setRequestPath("AuditEvent");
		requestDetails.setRequestContents(BODY);

		assertThat(RequestFingerprint.of(requestDetails)).isEqualTo(RequestFingerprint.of("POST", "AuditEvent", BODY));
	}
}
