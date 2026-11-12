package ca.uhn.fhir.jpa.starter.koppeltaal.util;

import ca.uhn.fhir.rest.api.Constants;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The trace headers of TOP-KT-011 as an AuditEvent records them: the trace id, the request id and, when the server
 * assigned its own request id, the client's original one as correlation id. A value that is absent stays absent.
 */
public record TraceHeaders(String traceId, String requestId, String correlationId) {

	/** The correlation-id extension of an AuditEvent holds a FHIR id. */
	private static final Pattern FHIR_ID = Pattern.compile("[A-Za-z0-9\\-.]{1,64}");

	/** Starts the id of every trace the server starts itself. */
	private static final String SERVER_TRACE_PREFIX = "server-";

	/**
	 * The trace headers of a request. Its correlation id is the client's own X-Request-ID, the same value the response
	 * returns as X-Correlation-Id, unless an AuditEvent cannot hold it.
	 */
	public static TraceHeaders fromRequest(RequestDetails request) {
		final String clientRequestId = request.getHeader(Constants.HEADER_REQUEST_ID);
		final boolean replacedByTheServer = clientRequestId != null && FHIR_ID.matcher(clientRequestId).matches()
				&& !clientRequestId.equals(request.getRequestId());
		return new TraceHeaders(request.getTransactionGuid(), request.getRequestId(),
				replacedByTheServer ? clientRequestId : null);
	}

	/**
	 * A trace the server starts itself, such as a cleanup run: a new trace id, and no request id or correlation id, as
	 * no request caused it.
	 */
	public static TraceHeaders newTrace() {
		return new TraceHeaders(SERVER_TRACE_PREFIX + UUID.randomUUID(), null, null);
	}

	/**
	 * Whether the server started this trace itself. Requests can carry such a trace on, as TOP-KT-006 asks of an
	 * application answering a notification, but none of them started it. A client that sends a trace with this prefix
	 * itself is treated the same.
	 */
	public static boolean isStartedByTheServer(String traceId) {
		return traceId != null && traceId.startsWith(SERVER_TRACE_PREFIX);
	}

	/** Lets a write the server makes itself carry this trace id, so the notifications it causes send it on. */
	public <T extends RequestDetails> T applyTraceIdTo(T request) {
		request.setTransactionGuid(traceId);
		return request;
	}
}
