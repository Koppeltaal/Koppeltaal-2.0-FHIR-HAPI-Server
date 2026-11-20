package nl.koppeltaal.hapi.autoconfigure;

import static ca.uhn.fhir.rest.server.ServletRequestTracing.ATTRIBUTE_REQUEST_ID;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

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

  @Test
  void keepsAnIdSetEarlier() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setAttribute(ATTRIBUTE_REQUEST_ID, "set-earlier");

    filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

    assertThat(request.getAttribute(ATTRIBUTE_REQUEST_ID)).isEqualTo("set-earlier");
  }
}
