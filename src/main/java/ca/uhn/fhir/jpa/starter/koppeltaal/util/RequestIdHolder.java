package ca.uhn.fhir.jpa.starter.koppeltaal.util;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * This holder contains the request id for incoming requests mapped to the trace-id.
 * This is needed as HAPI doesn't provide access to the request-id in the SUBSCRIPTION_BEFORE_DELIVERY Pointcut.
 *
 * This is a very hacky and error-prone in-memory solution to be able and set the X-Correlation-Id on outgoing subscription
 * calls. This is error-prone as new requests have the risk of overwriting the requestId before the interceptor
 * consumed the requestId.
 */
public class RequestIdHolder {

  private static final Map<String, String> traceIdToRequestIdMap = new HashMap<>();

  public static void addMapping(String traceId, String requestId) {
    traceIdToRequestIdMap.put(traceId, requestId);
  }

  public static Optional<String> getRequestId(String traceId) {
    return Optional.of(traceIdToRequestIdMap.get(traceId));
  }

  public static void clearRequestId(String traceId) {
    traceIdToRequestIdMap.remove(traceId);
  }
}
