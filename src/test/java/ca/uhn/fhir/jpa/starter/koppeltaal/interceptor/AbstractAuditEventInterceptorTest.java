package ca.uhn.fhir.jpa.starter.koppeltaal.interceptor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AbstractAuditEventInterceptorTest {

  @Test
  void getSiteFromForwardedHost() {
    assertEquals("example.com", AbstractAuditEventInterceptor.extractHost("https://localhost:8080", "example.com"));
  }

  @Test
  void getSiteFromRequestUrl() {
    assertEquals("example.com", AbstractAuditEventInterceptor.extractHost("https://example.com/fhir/Patient", null));
  }

  @Test
  void getSiteFromRequestUrlWithPort() {
    assertEquals("example.com", AbstractAuditEventInterceptor.extractHost("https://example.com:8443/fhir/Patient", null));
  }

  @Test
  void getSiteFallbackOnMalformedUrl() {
    assertEquals("localhost", AbstractAuditEventInterceptor.extractHost("not-a-url", null));
  }
}
