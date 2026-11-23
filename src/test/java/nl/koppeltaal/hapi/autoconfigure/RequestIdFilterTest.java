package nl.koppeltaal.hapi.autoconfigure;

import static ca.uhn.fhir.rest.server.ServletRequestTracing.ATTRIBUTE_REQUEST_ID;
import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.DispatcherType;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.util.WebUtils;

class RequestIdFilterTest {

  private static final String UUID_FORMAT = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

  private final RequestIdFilter filter = new RequestIdFilter();

  @Test
  void givesARequestWithoutIdAUuidAndIgnoresTheClientHeader() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("X-Request-Id", "client-request-1");

    filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

    assertThat((String) request.getAttribute(ATTRIBUTE_REQUEST_ID)).matches(UUID_FORMAT);
  }

  @Test
  void replacesABlankId() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setAttribute(ATTRIBUTE_REQUEST_ID, " ");

    filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

    assertThat((String) request.getAttribute(ATTRIBUTE_REQUEST_ID)).matches(UUID_FORMAT);
  }

  /** The fork's RestfulServer created the id whatever the dispatch type; a request can reach FHIR through these. */
  @Test
  void givesAnErrorDispatchARequestId() throws Exception {
    assertThat(requestIdAfterDispatch(DispatcherType.ERROR)).matches(UUID_FORMAT);
  }

  @Test
  void givesAnAsyncDispatchARequestId() throws Exception {
    assertThat(requestIdAfterDispatch(DispatcherType.ASYNC)).matches(UUID_FORMAT);
  }

  private String requestIdAfterDispatch(DispatcherType dispatcherType) throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setDispatcherType(dispatcherType);
    if (dispatcherType == DispatcherType.ERROR) {
      // the container sets this on an error dispatch; Spring's OncePerRequestFilter recognises the dispatch by it
      request.setAttribute(WebUtils.ERROR_REQUEST_URI_ATTRIBUTE, "/fhir/DEFAULT/Patient");
    }
    filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
    return (String) request.getAttribute(ATTRIBUTE_REQUEST_ID);
  }

  @Test
  void keepsAnIdSetEarlier() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setAttribute(ATTRIBUTE_REQUEST_ID, "set-earlier");

    filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

    assertThat(request.getAttribute(ATTRIBUTE_REQUEST_ID)).isEqualTo("set-earlier");
  }
}
