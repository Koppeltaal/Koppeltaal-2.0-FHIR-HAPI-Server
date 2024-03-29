package ca.uhn.fhir.jpa.starter.koppeltaal.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * This holder contains the request state for incoming requests mapped to the trace-id.
 * This is needed as HAPI doesn't provide access to the state in the SUBSCRIPTION_BEFORE_DELIVERY Pointcut.
 *
 * This is a very hacky and error-prone in-memory solution to be able and set the X-Correlation-Id on outgoing subscription
 * calls. This is error-prone as new requests have the risk of overwriting the requestId before the interceptor
 * consumed the requestId.
 */
@Component
public class RequestIdHolder {

  private static final Logger LOG = LoggerFactory.getLogger(RequestIdHolder.class);

  private final Map<String, String> traceIdToRequestIdMap = new HashMap<>();
  private final Map<String, String> traceIdToTenantIdMap = new HashMap<>();

  public void addMapping(String traceId, String requestId, String tenantId) {
    LOG.info("Mapping trace id [{}] to request id [{}] and tenant id [{}]", traceId, requestId, tenantId);
    traceIdToRequestIdMap.put(traceId, requestId);
    traceIdToTenantIdMap.put(traceId, tenantId);

    autoCleanup(traceId);
  }

  public Optional<String> getRequestId(String traceId) {

    boolean hasRequestId = traceIdToRequestIdMap.containsKey(traceId);

    if(hasRequestId) {
      String requestId = traceIdToRequestIdMap.get(traceId);
      LOG.info("Found request id [{}] found based on trace id [{}]", requestId, traceId);
      return Optional.of(requestId);
    }

    LOG.info("Did not find request id based on trace id [{}]", traceId);
    return Optional.empty();
  }

  public Optional<String> getTenantId(String traceId) {

    boolean hasTenantId = traceIdToTenantIdMap.containsKey(traceId);

    if(hasTenantId) {
      String requestId = traceIdToTenantIdMap.get(traceId);
      LOG.info("Found request id [{}] found based on tenant id [{}]", requestId, traceId);
      return Optional.of(requestId);
    }

    LOG.info("Did not find tenant id based on trace id [{}]", traceId);
    return Optional.empty();
  }

  public void clearIds(String traceId) {
    LOG.info("Clearing request and tenant id mapped to trace id [{}]", traceId);
    traceIdToRequestIdMap.remove(traceId);
    traceIdToTenantIdMap.remove(traceId);
  }

  private void autoCleanup(String traceId) {

    LOG.info("Cleaning up trace id [{}] in 20 seconds", traceId);

    new Timer().schedule(new TimerTask() {
      @Override
      public void run() {
        clearIds(traceId);
      }
    }, 20 * 60 * 1000L); //cleanup after 20 minutes
  }
}
