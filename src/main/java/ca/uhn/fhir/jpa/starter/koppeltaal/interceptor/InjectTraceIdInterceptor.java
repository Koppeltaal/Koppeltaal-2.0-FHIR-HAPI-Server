package ca.uhn.fhir.jpa.starter.koppeltaal.interceptor;

import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.jpa.starter.koppeltaal.util.RequestIdHolder;
import ca.uhn.fhir.jpa.subscription.model.CanonicalSubscription;
import ca.uhn.fhir.jpa.subscription.model.ResourceDeliveryMessage;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.servlet.ServletRequestDetails;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.commons.lang3.StringUtils;
import org.hl7.fhir.r4.model.StringType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import static ca.uhn.fhir.jpa.starter.koppeltaal.interceptor.InjectCorrelationIdInterceptor.CORRELATION_HEADER_KEY;
import static ca.uhn.fhir.rest.api.Constants.HEADER_REQUEST_ID;

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
  private static final Logger LOG = LoggerFactory.getLogger(InjectTraceIdInterceptor.class);
  private static final Logger ourLog = LoggerFactory.getLogger(InjectTraceIdInterceptor.class);
  private final RequestIdHolder requestIdHolder;

  public InjectTraceIdInterceptor(RequestIdHolder requestIdHolder) {
    this.requestIdHolder = requestIdHolder;
  }

  @Hook(value = Pointcut.SERVER_INCOMING_REQUEST_POST_PROCESSED, order = -10)
  public void incomingRequestPreHandled(RequestDetails requestDetails, ServletRequestDetails servletRequestDetails) {
    injectTraceId(requestDetails, servletRequestDetails);
  }

  @Hook(value = Pointcut.SERVER_OUTGOING_RESPONSE, order = Integer.MAX_VALUE)
  public void injectTraceIdOnSuccess(RequestDetails requestDetails, ServletRequestDetails servletRequestDetails) {
    injectTraceId(requestDetails, servletRequestDetails);
  }

  @Hook(value = Pointcut.SERVER_OUTGOING_FAILURE_OPERATIONOUTCOME, order = Integer.MAX_VALUE)
  public void injectTraceIdOnError(RequestDetails requestDetails, ServletRequestDetails servletRequestDetails) {
    injectTraceId(requestDetails, servletRequestDetails);
  }

  @Hook(value = Pointcut.SUBSCRIPTION_BEFORE_DELIVERY, order = Integer.MAX_VALUE)
  public void outgoingSubscriptionBeforeDelivery(ResourceDeliveryMessage message) {
    String transactionId = message.getTransactionId();

    LOG.info("Delivering subscription for traceId {}. Adding tracing headers", transactionId);

    CanonicalSubscription canonicalSubscription = message.getSubscription();
    String requestId = UUID.randomUUID().toString(); // async, so always generate a new requestId

    Optional<String> correlationIdOptional = requestIdHolder.getRequestId(transactionId);

    // There is no access to the response headers on the HttpServletResponse for
    // subscriptions. Adding them in-memory to the subscription headers.
    // Cleaning up to make sure the previous in-memory results aren't sent as well
    canonicalSubscription.setHeaders(
      canonicalSubscription.getHeaders().stream()
        .filter(header ->
          !header.startsWith(TRACE_ID_HEADER_KEY)
            && !header.startsWith(HEADER_REQUEST_ID)
            && !header.startsWith(CORRELATION_HEADER_KEY)
        )
        .map(StringType::new)
        .collect(Collectors.toList()));


    canonicalSubscription.addHeader(TRACE_ID_HEADER_KEY + ": " + transactionId);
    canonicalSubscription.addHeader(HEADER_REQUEST_ID + ": " + requestId);
    correlationIdOptional
      .ifPresent(correlationId -> canonicalSubscription.addHeader(CORRELATION_HEADER_KEY + ": " + correlationId));
  }

  private void injectTraceId(RequestDetails requestDetails, ServletRequestDetails servletRequestDetails) {
    String traceId = requestDetails.getTransactionGuid();

    if (StringUtils.isBlank(traceId)) {

      traceId = requestDetails.getHeader(TRACE_ID_HEADER_KEY);

      if (StringUtils.isBlank(traceId)) {
        traceId = UUID.randomUUID().toString();
      }

      requestDetails.setTransactionGuid(traceId);
    }

    HttpServletResponse response = servletRequestDetails.getServletResponse();
    response.setHeader(TRACE_ID_HEADER_KEY, traceId);
  }

}
