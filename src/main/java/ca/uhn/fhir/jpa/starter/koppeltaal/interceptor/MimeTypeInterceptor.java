package ca.uhn.fhir.jpa.starter.koppeltaal.interceptor;

import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.rest.server.exceptions.UnclassifiedServerFailureException;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpHeaders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

import static ca.uhn.fhir.jpa.starter.koppeltaal.interceptor.CapabilityStatementInterceptor.SUPPORTED_MIME_TYPES;
import static ca.uhn.fhir.jpa.starter.koppeltaal.interceptor.CapabilityStatementInterceptor.UNSUPPORTED_MIME_TYPES;

/**
 * Interceptor that makes sure the <code>Content-Type</code> header is supported
 */
@Interceptor
@Component
public class MimeTypeInterceptor {
	private static final Logger LOG = LoggerFactory.getLogger(MimeTypeInterceptor.class);


	@Hook(Pointcut.SERVER_INCOMING_REQUEST_PRE_PROCESSED)
	protected void ensureMimeTypeIsSupported(HttpServletRequest request, HttpServletResponse response) throws IOException {
    validateMineTypeSupport(HttpHeaders.CONTENT_TYPE, request);
    validateMineTypeSupport(HttpHeaders.ACCEPT, request);
  }

  private static void validateMineTypeSupport(String header, HttpServletRequest request) {

    String rawHeaderValue = request.getHeader(header);

    if(StringUtils.isBlank(rawHeaderValue)) return; //server defaults to application/fhir+json

    String[] headerValues = rawHeaderValue.split(",");

    boolean foundUnsupported = false;
    boolean foundSupported = false;

    for (String headerValue : headerValues) {

      // the Accept header can contain additional information, seperated with a semicolon
      headerValue = StringUtils.substringBefore(headerValue, ";");

      if(SUPPORTED_MIME_TYPES.contains(headerValue)) {
        foundSupported = true;
        break;
      }

      //known unsupported mime types
      if(UNSUPPORTED_MIME_TYPES.contains(headerValue)) {
        foundUnsupported = true;
      }
    }

    if(foundSupported) return;

    if(foundUnsupported) {
      LOG.warn("Client sent known unsupported Accept header value: [{}]", rawHeaderValue);
      throw new UnclassifiedServerFailureException(415, String.format("Unsupported Media Type [%s] provided in header [%s]. Supported media types are %s", rawHeaderValue, header, SUPPORTED_MIME_TYPES));
    }

    //alternatively, we don't mark the mime type as unknown
    throw new UnclassifiedServerFailureException(400, String.format("Unknown Media Type [%s] provided in header [%s]. Supported media types are %s", rawHeaderValue, header, SUPPORTED_MIME_TYPES));
    }

}
