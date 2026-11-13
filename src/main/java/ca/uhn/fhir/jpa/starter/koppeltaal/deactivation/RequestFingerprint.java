package ca.uhn.fhir.jpa.starter.koppeltaal.deactivation;

import ca.uhn.fhir.rest.api.server.RequestDetails;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.apache.commons.lang3.StringUtils;

/** Identifies a request by what the client sent: method, path and body. */
final class RequestFingerprint {

	private RequestFingerprint() {
	}

	/** HAPI keeps the body once read, so this reads nothing from the client. */
	static String of(RequestDetails requestDetails) {
		return of(String.valueOf(requestDetails.getRequestType()), requestDetails.getRequestPath(),
				requestDetails.getRequestContentsIfLoaded());
	}

	static String of(String method, String path, byte[] body) {
		MessageDigest digest = sha256();
		digest.update((method + " " + StringUtils.defaultString(path) + "\n").getBytes(StandardCharsets.UTF_8));
		if (body != null) {
			digest.update(body);
		}
		return HexFormat.of().formatHex(digest.digest());
	}

	private static MessageDigest sha256() {
		try {
			return MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is not available", e);
		}
	}
}
