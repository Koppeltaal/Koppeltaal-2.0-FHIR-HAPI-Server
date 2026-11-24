package nl.koppeltaal.hapi.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.interceptor.api.IBaseInterceptorBroadcaster.IInvoker;
import ca.uhn.fhir.interceptor.api.IInterceptorService;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.interceptor.executor.BaseInterceptorService;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.interceptor.CascadingDeleteInterceptor;
import ca.uhn.fhir.jpa.interceptor.validation.IRepositoryValidatingRule;
import ca.uhn.fhir.jpa.interceptor.validation.RepositoryValidatingInterceptor;
import ca.uhn.fhir.jpa.starter.Application;
import nl.koppeltaal.hapi.service.Oauth2AccessTokenService;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import ca.uhn.fhir.rest.server.RestfulServer;
import com.google.common.collect.Multimap;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.hl7.fhir.r4.model.CapabilityStatement;
import org.hl7.fhir.r4.model.Device;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Pins the server's wiring with production's switches (profile "contract"): the hook order per registry and pointcut,
 * the repository validation rules, the providers, and the HTTP behavior that hangs on the wiring. The snapshots in
 * src/test/resources/contract change only on purpose, for example with a HAPI upgrade: regenerate them with
 * -Dcontract.update=true and review the diff.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = {Application.class}, properties = {
  "spring.datasource.url=jdbc:h2:mem:server-wiring-contract",
  "hapi.fhir.fhir_version=r4",
  "hapi.fhir.cr_enabled=false",
  "hapi.fhir.mdm_enabled=false",
  "hapi.fhir.subscription.websocket_enabled=false"
})
@ActiveProfiles("contract")
class ServerWiringContractTest {

  private static final Path INTERCEPTOR_ORDER = Path.of("src/test/resources/contract/interceptor-order.txt");
  private static final Path VALIDATION_RULES = Path.of("src/test/resources/contract/repository-validation-rules.txt");
  private static final Path PROVIDERS = Path.of("src/test/resources/contract/providers.txt");
  private static final Pattern UUID_FORMAT =
    Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

  @LocalServerPort
  private int port;

  @Autowired
  private RestfulServer restfulServer;

  @Autowired
  private IInterceptorService jpaInterceptorService;

  @Autowired
  private RepositoryValidatingInterceptor repositoryValidatingInterceptor;

  @Autowired
  private IFhirResourceDao<Device> deviceDao;

  @MockBean
  private Oauth2AccessTokenService oauth2AccessTokenService; // keeps the JWKS endpoint out; no request here needs a token

  private final HttpClient http = HttpClient.newHttpClient();

  @Test
  void interceptorsRunInTheRecordedOrder() throws Exception {
    servletStarted();
    assertMatchesSnapshot(INTERCEPTOR_ORDER,
      describe("server", restfulServer.getInterceptorService()) + describe("jpa", jpaInterceptorService));
  }

  @Test
  void repositoryValidationHasTheRecordedRules() throws Exception {
    @SuppressWarnings("unchecked")
    Multimap<String, IRepositoryValidatingRule> rules = (Multimap<String, IRepositoryValidatingRule>)
      ReflectionTestUtils.getField(repositoryValidatingInterceptor, "myRules");
    StringBuilder actual = new StringBuilder(repositoryValidatingInterceptor.describeRules())
      .append("\n--- evaluation order per resource type\n");
    new TreeMap<>(rules.asMap()).forEach((type, typeRules) -> {
      actual.append(type).append('\n');
      typeRules.forEach(rule -> actual.append("  ").append(rule).append('\n'));
    });
    assertMatchesSnapshot(VALIDATION_RULES, normalize(actual.toString()));
  }

  /** The registered providers, which include GraphQL when it is on, and the resource types the server offers. */
  @Test
  void theServerOffersTheRecordedProviders() throws Exception {
    servletStarted();
    List<String> providers = new java.util.ArrayList<>();
    restfulServer.getPlainProviders().forEach(provider -> providers.add("plain " + provider.getClass().getName()));
    restfulServer.getResourceProviders().forEach(provider ->
      providers.add("resource " + provider.getResourceType().getSimpleName() + " " + provider.getClass().getName()));
    providers.sort(null);
    assertMatchesSnapshot(PROVIDERS, normalize(String.join("\n", providers) + "\n"));
  }

  @Test
  void cascadingDeletesAreWiredOnceWithResourceOriginChecks() throws Exception {
    servletStarted();
    List<Object> registered = restfulServer.getInterceptorService().getAllRegisteredInterceptors();
    assertEquals(1, registered.stream().filter(CascadingDeleteInterceptor.class::isInstance).count());
    assertTrue(registered.stream()
      .anyMatch(interceptor -> interceptor.getClass().getSimpleName().equals("ResourceOriginCascadingDeleteInterceptor")));
  }

  @Test
  void deleteFlowLifecycleAuditRunsOnTheJpaRegistryOnly() throws Exception {
    servletStarted();
    assertTrue(jpaInterceptorService.getAllRegisteredInterceptors().stream()
      .anyMatch(interceptor -> interceptor.getClass().getSimpleName().equals("DeleteFlowLifecycleAuditInterceptor")));
    assertTrue(restfulServer.getInterceptorService().getAllRegisteredInterceptors().stream()
      .noneMatch(interceptor -> interceptor.getClass().getSimpleName().equals("DeleteFlowLifecycleAuditInterceptor")));
  }

  @Test
  void replacesTheClientRequestId() throws Exception {
    HttpResponse<String> response = get("/fhir/DEFAULT/metadata", Map.of("X-Request-Id", "client-request-1"));
    assertEquals(200, response.statusCode());
    assertTrue(response.body().contains("\"CapabilityStatement\""));
    String requestId = response.headers().firstValue("X-Request-Id").orElseThrow();
    assertTrue(UUID_FORMAT.matcher(requestId).matches(), "request id " + requestId);
    assertEquals("client-request-1", response.headers().firstValue("X-Correlation-Id").orElse(null));
  }

  @Test
  void corsPreflightAllowsIfMatch() throws Exception {
    HttpRequest preflight = HttpRequest.newBuilder(uri("/fhir/DEFAULT/Patient/1"))
      .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
      .header("Origin", "https://app.example.org")
      .header("Access-Control-Request-Method", "PUT")
      .header("Access-Control-Request-Headers", "if-match")
      .build();
    HttpResponse<String> response = http.send(preflight, HttpResponse.BodyHandlers.ofString());
    assertEquals(200, response.statusCode());
    String allowedHeaders = response.headers().firstValue("Access-Control-Allow-Headers").orElse("");
    assertTrue(allowedHeaders.toLowerCase().contains("if-match"), "allowed headers " + allowedHeaders);
  }

  @Test
  void serverAssignedIdsAreUuids() throws Exception {
    servletStarted();
    String id = deviceDao.create(new Device(), new SystemRequestDetails()).getId().getIdPart();
    assertTrue(UUID_FORMAT.matcher(id).matches(), "id " + id);
  }

  /** CapabilityStatement.date identifies the deployed build; the image's WAR is upstream's, so the extension reports its own. */
  @Test
  void metadataReportsTheExtensionBuildTime() throws Exception {
    HttpResponse<String> response = get("/fhir/DEFAULT/metadata", Map.of());
    assertEquals(200, response.statusCode());
    CapabilityStatement capabilityStatement =
      FhirContext.forR4Cached().newJsonParser().parseResource(CapabilityStatement.class, response.body());
    assertEquals(Instant.parse(extensionBuildTime()),
      capabilityStatement.getDate().toInstant());
  }

  @Test
  void theRouteWithoutDefaultIsGone() throws Exception {
    assertEquals(404, get("/fhir/metadata", Map.of()).statusCode());
  }

  private static String extensionBuildTime() throws Exception {
    Properties buildInfo = new Properties();
    try (InputStream in = ServerWiringContractTest.class.getResourceAsStream("/nl/koppeltaal/hapi/build-info.properties")) {
      assertTrue(in != null, "the build writes nl/koppeltaal/hapi/build-info.properties");
      buildInfo.load(in);
    }
    return buildInfo.getProperty("build-time");
  }

  /** Makes sure the FHIR servlet has started before its wiring is inspected. */
  private void servletStarted() throws Exception {
    assertEquals(200, get("/fhir/DEFAULT/metadata", Map.of()).statusCode());
  }

  private HttpResponse<String> get(String route, Map<String, String> headers) throws Exception {
    HttpRequest.Builder request = HttpRequest.newBuilder(uri(route)).header("Accept", "application/fhir+json");
    headers.forEach(request::header);
    return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private URI uri(String route) {
    return URI.create("http://localhost:" + port + route);
  }

  @SuppressWarnings("unchecked")
  private static String describe(String registry, IInterceptorService service) {
    BaseInterceptorService<Pointcut> interceptors = (BaseInterceptorService<Pointcut>) service;
    StringBuilder out = new StringBuilder();
    for (Pointcut pointcut : Pointcut.values()) {
      List<IInvoker> invokers = interceptors.getInvokersForPointcut(pointcut);
      if (!invokers.isEmpty()) {
        out.append(registry).append(' ').append(pointcut.name()).append('\n');
        for (IInvoker invoker : invokers) {
          String description = invoker.getHookDescription();
          // An anonymous hook describes itself only by identity, which cannot pin an order. The server registers none
          // today; if one appears, extend this serialization instead of hiding it.
          if (description.matches(".*@[0-9a-f]+$")) {
            throw new AssertionError("anonymous hook on " + registry + " " + pointcut + ": " + description);
          }
          out.append("  ").append(invoker.getOrder()).append(' ').append(normalize(description)).append('\n');
        }
      }
    }
    return out.toString();
  }

  /** Only the Koppeltaal package moves; identity hashes and lambda class names get a stable form. Anonymous hooks never
   * get here: describe() fails on them first. */
  private static String normalize(String value) {
    return value.replace("nl.koppeltaal.hapi.", "kt2.")
      .replaceAll("@[0-9a-f]+", "").replaceAll("\\$\\$Lambda[^.\\s,\\]]*", "\\$\\$Lambda");
  }

  private static void assertMatchesSnapshot(Path snapshot, String actual) throws Exception {
    if (Boolean.getBoolean("contract.update")) {
      Files.createDirectories(snapshot.getParent());
      Files.writeString(snapshot, actual);
    }
    assertEquals(Files.readString(snapshot), actual,
      "the wiring changed; " + snapshot + " is pinned: if the change is intended, regenerate it with "
        + "-Dcontract.update=true and review the diff");
  }
}
