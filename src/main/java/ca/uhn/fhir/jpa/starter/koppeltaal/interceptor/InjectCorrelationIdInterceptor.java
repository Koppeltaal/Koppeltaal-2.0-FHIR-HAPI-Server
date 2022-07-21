package ca.uhn.fhir.jpa.starter.koppeltaal.interceptor;

import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.rest.api.Constants;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.servlet.ServletRequestDetails;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import javax.servlet.http.HttpServletResponse;

/**
 * <p>
 *   The request id in <code>X-Request-Id</code> header is purely to help connect between requests and logs/audit trails.
 *   The client can assign an id to the request, and send that in the <code>X-Request-Id</code> header. The server can either use that id or assign
 *   its own, which it returns as the <code>X-Request-Id</code> header in the response. When the server assigned id is different to
 *   the client assigned id, the server SHOULD also return the <code>X-Correlation-Id</code> header with the client's original id in it.
 * </p>
 * <br/>
 * <p>
 *   This interceptor eases the process and allows any interceptor code to simply modify the request id. This interceptor
 *   is executed as late as possible. If the request id is changed by server code, the correlation id will be provided.
 * </p>
 * <a href="https://hl7.org/fhir/http.html#custom" target="_blank">source</a>
 */
@Component
@Interceptor
public class InjectCorrelationIdInterceptor {
  private final static String CORRELATION_HEADER_KEY = "X-Correlation-Id";

  @Hook(value = Pointcut.SERVER_OUTGOING_RESPONSE, order = Integer.MAX_VALUE)
  public void injectCorrelationIdOnSuccess(RequestDetails requestDetails, ServletRequestDetails servletRequestDetails) {
    injectCorrelationId(requestDetails, servletRequestDetails);
  }

  @Hook(value = Pointcut.SERVER_OUTGOING_FAILURE_OPERATIONOUTCOME, order = Integer.MAX_VALUE)
  public void injectCorrelationIdOnError(RequestDetails requestDetails, ServletRequestDetails servletRequestDetails) {
    injectCorrelationId(requestDetails, servletRequestDetails);
  }

  private void injectCorrelationId(RequestDetails requestDetails, ServletRequestDetails servletRequestDetails) {
    final String serverRequestId = requestDetails.getRequestId();

    HttpServletResponse response = servletRequestDetails.getServletResponse();

    final String clientRequestId = servletRequestDetails.getHeader(Constants.HEADER_REQUEST_ID);
    final String clientCorrelationId = servletRequestDetails.getHeader(CORRELATION_HEADER_KEY);

    final boolean serverChangedRequestId = !StringUtils.equals(clientRequestId, serverRequestId);
    if (StringUtils.isNotBlank(clientRequestId) && serverChangedRequestId) {
      response.addHeader(CORRELATION_HEADER_KEY, clientRequestId);
    } else if(StringUtils.isNotBlank(clientCorrelationId)) {
      response.addHeader(CORRELATION_HEADER_KEY, clientCorrelationId);
    }
  }
}
