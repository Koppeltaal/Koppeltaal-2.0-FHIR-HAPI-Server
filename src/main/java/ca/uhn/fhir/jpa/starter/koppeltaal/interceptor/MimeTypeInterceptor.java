package ca.uhn.fhir.jpa.starter.koppeltaal.interceptor;

import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.rest.server.exceptions.UnclassifiedServerFailureException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpHeaders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;

import static ca.uhn.fhir.jpa.starter.koppeltaal.interceptor.CapabilityStatementInterceptor.*;

/**
 * Interceptor that makes sure the <code>Content-Type</code> header is supported
 */
@Interceptor
@Component
public class MimeTypeInterceptor {
  private static final Logger LOG = LoggerFactory.getLogger(MimeTypeInterceptor.class);

  @Hook(Pointcut.SERVER_INCOMING_REQUEST_PRE_PROCESSED)
  protected void ensureMimeTypeIsSupported(HttpServletRequest request, HttpServletResponse response) throws IOException {
    validateMineTypeSupport(HttpHeaders.CONTENT_TYPE, request, 415);
    validateMineTypeSupport(HttpHeaders.ACCEPT, request, 406);
  }

  private static void validateMineTypeSupport(String header, HttpServletRequest request, int errorStatusCode) {

    String rawHeaderValue = request.getHeader(header);

    if (StringUtils.isBlank(rawHeaderValue)) return; //server defaults to application/fhir+json

    String[] headerValues = rawHeaderValue.split(",");

    boolean foundUnsupported = false;
    boolean foundSupported = false;

    for (String headerValue : headerValues) {

      // the Accept header can contain additional information, separated with a semicolon
      headerValue = StringUtils.substringBefore(headerValue, ";");

      if (
        ("PATCH".equals(request.getMethod()) && SUPPORTED_PATCH_MIME_TYPES.contains(headerValue)) ||
          (!"PATCH".equals(request.getMethod()) && SUPPORTED_MIME_TYPES.contains(headerValue))
      ) {
        foundSupported = true;
        break;
      }

      //known unsupported mime types
      if (UNSUPPORTED_MIME_TYPES.contains(headerValue)) {
        foundUnsupported = true;
      }
    }

    if (foundSupported) return;

    if (foundUnsupported) {
      LOG.warn("Client sent known unsupported Accept header value: [{}]", rawHeaderValue);
      throw new UnclassifiedServerFailureException(errorStatusCode, String.format("Unsupported Media Type [%s] provided in header [%s]. Supported media types are %s", rawHeaderValue, header, SUPPORTED_MIME_TYPES));
    }

    //alternatively, we don't mark the mime type as unknown
    throw new UnclassifiedServerFailureException(errorStatusCode, String.format("Unknown Media Type [%s] provided in header [%s]. Supported media types are %s", rawHeaderValue, header, SUPPORTED_MIME_TYPES));
  }

}
