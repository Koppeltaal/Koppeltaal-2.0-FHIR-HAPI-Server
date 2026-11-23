package nl.koppeltaal.hapi.autoconfigure;

import static ca.uhn.fhir.rest.server.ServletRequestTracing.ATTRIBUTE_REQUEST_ID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gives every FHIR request a UUID as request id. HAPI reads this attribute before it would adopt the client's
 * X-Request-ID; the client's value then becomes the correlation id (TraceHeaders). It also runs for the .well-known
 * servlet under the same path, which ignores the attribute.
 */
public class RequestIdFilter extends OncePerRequestFilter {

  /** HAPI's RestfulServer used to create the id for every dispatch; a request can also reach FHIR through these. */
  @Override
  protected boolean shouldNotFilterAsyncDispatch() {
    return false;
  }

  @Override
  protected boolean shouldNotFilterErrorDispatch() {
    return false;
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (!(request.getAttribute(ATTRIBUTE_REQUEST_ID) instanceof String requestId) || requestId.isBlank()) {
      request.setAttribute(ATTRIBUTE_REQUEST_ID, UUID.randomUUID().toString());
    }
    chain.doFilter(request, response);
  }
}
