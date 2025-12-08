package ca.uhn.fhir.jpa.starter.koppeltaal.interceptor;

import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import ca.uhn.fhir.rest.server.method.ResourceParameter;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.Charset;

/**
 * Interceptor that catches {@link JsonSyntaxException} from Gson during request body parsing
 * and converts it to a proper HTTP 400 Bad Request response.
 * <p>
 * This is a workaround for a bug in HAPI FHIR's {@code ValidatorWrapper} where JSON parsing
 * errors are not caught (unlike XML parsing errors), causing them to propagate as uncaught
 * exceptions and result in HTTP 500 errors instead of HTTP 400.
 * <p>
 * This interceptor runs at {@link Pointcut#SERVER_INCOMING_REQUEST_POST_PROCESSED} and min order
 * which is before the validation interceptor, allowing us to detect invalid JSON early and return
 * the appropriate error response.
 * <p>
 *
 * TODO: Delete this interceptor when HAPI has try/catch on GSON parsing included
 *
 * @see ca.uhn.fhir.rest.server.interceptor.RequestValidatingInterceptor
 */
@Interceptor
@Component
public class JsonSyntaxExceptionInterceptor {

  private static final Logger LOG = LoggerFactory.getLogger(JsonSyntaxExceptionInterceptor.class);
  private static final Gson GSON = new Gson();

  @Hook(value = Pointcut.SERVER_INCOMING_REQUEST_POST_PROCESSED, order = Integer.MIN_VALUE)
  public void validateJsonSyntax(RequestDetails requestDetails) {
    String contentType = requestDetails.getHeader("Content-Type");
    if (contentType == null || !contentType.contains("json")) {
      return;
    }

    Charset charset = ResourceParameter.determineRequestCharset(requestDetails);
    String requestText = new String(requestDetails.loadRequestContents(), charset);

    if (StringUtils.isBlank(requestText)) {
      return;
    }

    try {
      GSON.fromJson(requestText, JsonObject.class);
    } catch (JsonSyntaxException e) {
      LOG.warn("Invalid JSON syntax in request body: {}", e.getMessage());
      throw new InvalidRequestException("Invalid JSON syntax: " + e.getMessage());
    }
  }
}