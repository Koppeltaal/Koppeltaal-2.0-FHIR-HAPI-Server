package ca.uhn.fhir.jpa.starter.koppeltaal.deactivation;

import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.starter.koppeltaal.interceptor.JwtSecurityInterceptor;
import ca.uhn.fhir.jpa.starter.koppeltaal.deactivation.ClientDeactivationProperties.Mode;
import ca.uhn.fhir.jpa.starter.koppeltaal.deactivation.RepeatedRejectionDetector.Detection;
import ca.uhn.fhir.rest.api.RestOperationTypeEnum;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import ca.uhn.fhir.rest.server.exceptions.BaseServerResponseException;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import ca.uhn.fhir.rest.server.exceptions.UnprocessableEntityException;
import ca.uhn.fhir.rest.server.servlet.ServletRequestDetails;
import com.auth0.jwt.JWT;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.commons.lang3.StringUtils;
import org.hl7.fhir.r4.model.Device;
import org.hl7.fhir.r4.model.IdType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Stops a client that keeps resending write requests this server rejects (see {@link RepeatedRejectionDetector}), and
 * refuses every request of a client whose Device is inactive, which is how domain-admin marks a client that is not
 * approved.
 */
@Interceptor
public class ClientDeactivationInterceptor {

	private static final Logger LOG = LoggerFactory.getLogger(ClientDeactivationInterceptor.class);

	private final ClientDeactivationProperties properties;
	private final RepeatedRejectionDetector detector;
	private final DomainAdminDeactivationClient domainAdmin;
	private final DaoRegistry daoRegistry;
	private final String domainAdminClientId;
	private final Clock clock;

	/** Clients detected in enforce mode, refused here until domain-admin confirms their deactivation. */
	private final Set<String> locallyBlocked = ConcurrentHashMap.newKeySet();
	private final Map<String, Instant> lastReported = new ConcurrentHashMap<>();
	private final Map<String, DeviceStatus> deviceStatuses = new ConcurrentHashMap<>();
	/** Orders Device reads and confirmations; a status read before a client's last confirmation no longer counts. */
	private final AtomicLong sequence = new AtomicLong();
	private final Map<String, Long> confirmations = new ConcurrentHashMap<>();

	private record DeviceStatus(boolean inactive, Instant readAt, long readSequence) {
	}

	public ClientDeactivationInterceptor(ClientDeactivationProperties properties, RepeatedRejectionDetector detector,
			DomainAdminDeactivationClient domainAdmin, DaoRegistry daoRegistry, String domainAdminClientId, Clock clock) {
		this.properties = properties;
		this.detector = detector;
		this.domainAdmin = domainAdmin;
		this.daoRegistry = daoRegistry;
		this.domainAdminClientId = domainAdminClientId;
		this.clock = clock;
	}

	/**
	 * Runs right after the {@link JwtSecurityInterceptor} validated the token on this pointcut (order 0), before the
	 * MimeTypeInterceptor (order 2) and before any SERVER_INCOMING_REQUEST_POST_PROCESSED hook reads the body.
	 */
	@Hook(value = Pointcut.SERVER_INCOMING_REQUEST_PRE_PROCESSED, order = 1)
	public void refuseInactiveClient(HttpServletRequest request) {
		if (properties.getMode() == Mode.OFF) {
			return;
		}
		Optional<String> clientId = requesterClientId(request);
		if (clientId.isPresent() && isRefused(clientId.get())) {
			throw new InactiveClientException();
		}
	}

	@Hook(Pointcut.SERVER_PRE_PROCESS_OUTGOING_EXCEPTION)
	public void countRejectedWrite(ServletRequestDetails requestDetails, Throwable throwable) {
		if (properties.getMode() == Mode.OFF) {
			return;
		}
		try {
			if (isRejectedWrite(requestDetails, throwable)) {
				requesterClientId(requestDetails.getServletRequest())
						.filter(clientId -> !isDomainAdmin(clientId))
						.flatMap(clientId -> detector.record(clientId, RequestFingerprint.of(requestDetails)))
						.ifPresent(this::act);
			}
		} catch (RuntimeException e) {
			// counting must never change the response to the request
			LOG.error("Failed to count a rejected write request", e);
		}
	}

	private static boolean isRejectedWrite(ServletRequestDetails requestDetails, Throwable throwable) {
		RestOperationTypeEnum operation = requestDetails.getRestOperationType();
		return (operation == RestOperationTypeEnum.CREATE || operation == RestOperationTypeEnum.UPDATE)
				&& throwable instanceof BaseServerResponseException rejection
				&& (rejection.getStatusCode() == InvalidRequestException.STATUS_CODE
						|| rejection.getStatusCode() == UnprocessableEntityException.STATUS_CODE);
	}

	private void act(Detection detection) {
		String clientId = detection.clientId();
		String reason = detection.reason();
		LOG.warn("Client [{}] keeps resending rejected requests (detection {}): {} rejected writes, {} distinct, since {}",
				clientId, detection.id(), detection.rejections(), detection.distinctRequests(), detection.windowStart());
		if (properties.getMode() == Mode.ENFORCE) {
			if (locallyBlocked.add(clientId)) {
				domainAdmin.report(clientId, DomainAdminDeactivationClient.Mode.ENFORCE, detection.id(), reason,
						() -> confirmDeactivation(clientId));
			}
		} else if (reportIsDue(clientId)) {
			domainAdmin.report(clientId, DomainAdminDeactivationClient.Mode.REPORT, detection.id(), reason, () -> { });
		}
	}

	/**
	 * Domain-admin confirms only once Device/&lt;client id&gt; is inactive, so the Device status takes over from the local
	 * block, also across restarts, and a re-approval in domain-admin takes effect here.
	 */
	private void confirmDeactivation(String clientId) {
		// first invalidate every status read so far, also one still in flight, then lift the block
		confirmations.put(clientId, sequence.incrementAndGet());
		locallyBlocked.remove(clientId);
		LOG.info("Domain-admin confirmed the deactivation of client [{}]", clientId);
	}

	private boolean reportIsDue(String clientId) {
		Instant now = clock.instant();
		boolean[] due = {false};
		lastReported.compute(clientId, (id, last) -> {
			if (last == null || !now.isBefore(last.plus(properties.getReportInterval()))) {
				due[0] = true;
				return now;
			}
			return last;
		});
		return due[0];
	}

	private boolean isRefused(String clientId) {
		return !isDomainAdmin(clientId) && (locallyBlocked.contains(clientId) || deviceIsInactive(clientId));
	}

	private boolean isDomainAdmin(String clientId) {
		return StringUtils.equals(clientId, domainAdminClientId);
	}

	private boolean deviceIsInactive(String clientId) {
		Instant now = clock.instant();
		DeviceStatus cached = deviceStatuses.get(clientId);
		if (cached != null
				&& cached.readSequence() > confirmations.getOrDefault(clientId, 0L)
				&& now.isBefore(cached.readAt().plus(properties.getDeviceStatusCacheTtl()))) {
			return cached.inactive();
		}
		long readSequence = sequence.incrementAndGet();
		boolean inactive = readDeviceInactive(clientId);
		deviceStatuses.put(clientId, new DeviceStatus(inactive, now, readSequence));
		return inactive;
	}

	private boolean readDeviceInactive(String clientId) {
		try {
			Device device = daoRegistry.getResourceDao(Device.class)
					.read(new IdType("Device", clientId), new SystemRequestDetails());
			return device.getStatus() == Device.FHIRDeviceStatus.INACTIVE;
		} catch (RuntimeException e) {
			// without a Device, creates fail with "Device not present"; a failing read must not lock every client out
			return false;
		}
	}

	private static Optional<String> requesterClientId(HttpServletRequest request) {
		if (request == null
				|| !(request.getAttribute(JwtSecurityInterceptor.VALIDATED_ACCESS_TOKEN_ATTRIBUTE) instanceof String token)
				|| StringUtils.isBlank(token)) {
			return Optional.empty();
		}
		return Optional.ofNullable(JWT.decode(token).getClaim("azp").asString()).filter(StringUtils::isNotBlank);
	}
}
