package ca.uhn.fhir.jpa.starter.koppeltaal.interceptor;

import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.servlet.ServletRequestDetails;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import javax.servlet.http.HttpServletResponse;

/**
 * Koppeltaal supports the use of trace-ids, provided by the <code>X-Trace-Id</code> tag.
 * This interceptor ensures it's sent back to the user when provided.
 *
 * <a href="https://www.w3.org/TR/trace-context-1/#trace-id" target="_blank">trace-id in w3c</a>
 */
@Component
@Interceptor
public class InjectTraceIdInterceptor {
  public final static String TRACE_ID_HEADER_KEY = "X-Trace-Id";

  @Hook(value = Pointcut.SERVER_OUTGOING_RESPONSE, order = Integer.MAX_VALUE)
  public void injectTraceIdOnSuccess(RequestDetails requestDetails, ServletRequestDetails servletRequestDetails) {
    injectTradeId(requestDetails, servletRequestDetails);
  }

  @Hook(value = Pointcut.SERVER_OUTGOING_FAILURE_OPERATIONOUTCOME, order = Integer.MAX_VALUE)
  public void injectTraceIdOnError(RequestDetails requestDetails, ServletRequestDetails servletRequestDetails) {
    injectTradeId(requestDetails, servletRequestDetails);
  }

  private void injectTradeId(RequestDetails requestDetails, ServletRequestDetails servletRequestDetails) {
    final String traceId = requestDetails.getTransactionGuid();

    if (StringUtils.isNotBlank(traceId)) {
      HttpServletResponse response = servletRequestDetails.getServletResponse();
      response.setHeader(TRACE_ID_HEADER_KEY, traceId);
    }
  }

}
