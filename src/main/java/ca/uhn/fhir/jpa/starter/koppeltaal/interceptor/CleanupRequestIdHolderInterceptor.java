package ca.uhn.fhir.jpa.starter.koppeltaal.interceptor;

import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.jpa.starter.koppeltaal.util.RequestIdHolder;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

/**
 * At the end of all requests, cleanup the {@link ca.uhn.fhir.jpa.starter.koppeltaal.util.RequestIdHolder}.
 */
@Component
@Interceptor
public class CleanupRequestIdHolderInterceptor {

  @Hook(value = Pointcut.SERVER_PROCESSING_COMPLETED)
  public void cleanup(RequestDetails requestDetails) {
    String traceId = requestDetails.getTransactionGuid();

    if(StringUtils.isNotBlank(traceId)) {
      RequestIdHolder.clearRequestId(traceId);
    }
  }

}
