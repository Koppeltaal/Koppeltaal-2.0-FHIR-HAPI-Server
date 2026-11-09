package ca.uhn.fhir.jpa.starter.koppeltaal;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.rest.server.util.ISearchParamRegistry;
import ca.uhn.fhir.jpa.starter.Application;
import ca.uhn.fhir.jpa.starter.koppeltaal.service.Oauth2AccessTokenService;
import ca.uhn.fhir.jpa.starter.koppeltaal.util.ResourceOriginUtil;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.client.api.ServerValidationModeEnum;
import ca.uhn.fhir.rest.client.interceptor.BearerTokenAuthInterceptor;
import ca.uhn.fhir.rest.server.exceptions.ForbiddenOperationException;
import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Enumerations;
import org.hl7.fhir.r4.model.Device;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.SearchParameter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Runs the resource-origin checks through the real server: HAPI's REST layer, the interceptors and the database. Only
 * the signature of the access token is not checked.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = {Application.class}, properties = {
  "spring.datasource.url=jdbc:h2:mem:resource-origin-server-test",
  "hapi.fhir.fhir_version=r4",
  "hapi.fhir.cr_enabled=false",
  "hapi.fhir.mdm_enabled=false",
  "hapi.fhir.subscription.websocket_enabled=false",
  "fhir.server.security.enabled=true"
})
class ResourceOriginServerTest {

  private static final String APP_A = "app-a";
  private static final String APP_B = "app-b";

  @LocalServerPort
  private int port;

  @MockBean
  private Oauth2AccessTokenService oauth2AccessTokenService;

  @Autowired
  private IFhirResourceDao<Device> deviceDao;

  @Autowired
  private IFhirResourceDao<SearchParameter> searchParameterDao;

  @Autowired
  private ISearchParamRegistry searchParamRegistry;

  private final FhirContext fhirContext = FhirContext.forR4Cached();

  @BeforeEach
  void setUp() {
    when(oauth2AccessTokenService.validateToken(anyString())).thenReturn(true);
    fhirContext.getRestfulClientFactory().setServerValidationMode(ServerValidationModeEnum.NEVER);

    // installed with the Koppeltaal ImplementationGuide, which this test does not install
    final SearchParameter resourceOrigin = new SearchParameter();
    resourceOrigin.setId("resource-origin-extension");
    resourceOrigin.setUrl("http://koppeltaal.nl/fhir/SearchParameter/resource-origin-extension");
    resourceOrigin.setCode("resource-origin");
    resourceOrigin.setStatus(Enumerations.PublicationStatus.ACTIVE);
    resourceOrigin.setType(Enumerations.SearchParamType.REFERENCE);
    resourceOrigin.addBase("Patient");
    resourceOrigin.setExpression("Patient.extension('" + ResourceOriginUtil.RESOURCE_ORIGIN_SYSTEM + "')");
    searchParameterDao.update(resourceOrigin, new SystemRequestDetails());
    searchParamRegistry.forceRefresh();

    for (String clientId : List.of(APP_A, APP_B)) {
      final Device device = new Device();
      device.setId(clientId);
      deviceDao.update(device, new SystemRequestDetails());
    }
  }

  @Test
  void searchOnlyReturnsTheResourcesOfTheOwnResourceOrigin() {
    final IGenericClient clientA = client(APP_A, ownScope(APP_A));
    final String patientA = createPatient(clientA);
    final String patientB = createPatient(client(APP_B, ownScope(APP_B)));

    final Bundle search = clientA.search().forResource(Patient.class).returnBundle(Bundle.class).execute();

    assertThat(ids(search)).contains(patientA).doesNotContain(patientB);
    assertThat(origins(search)).containsOnly("Device/" + APP_A);
  }

  @Test
  void typeHistoryIsNotSupported() {
    createPatient(client(APP_A, ownScope(APP_A)));

    for (String scope : List.of(ownScope(APP_A), "system/Patient.cruds")) {
      final IGenericClient client = client(APP_A, scope);

      assertThatThrownBy(() -> client.history().onType(Patient.class).returnBundle(Bundle.class).execute())
        .as("Patient/_history with scope %s", scope)
        .isInstanceOf(ForbiddenOperationException.class);
    }
  }

  @Test
  void instanceHistoryAndVreadRequireTheResourceOrigin() {
    final IGenericClient clientA = client(APP_A, ownScope(APP_A));
    final IGenericClient clientB = client(APP_B, ownScope(APP_B));
    final String patientA = createPatient(clientA);

    final Bundle history = clientA.history().onInstance(patientA).returnBundle(Bundle.class).execute();
    assertThat(ids(history)).containsOnly(patientA);

    assertThatThrownBy(() -> clientB.history().onInstance(patientA).returnBundle(Bundle.class).execute())
      .isInstanceOf(ForbiddenOperationException.class);
    assertThatThrownBy(() -> clientB.read().resource(Patient.class).withIdAndVersion(new IdType(patientA).getIdPart(), "1").execute())
      .isInstanceOf(ForbiddenOperationException.class);
  }

  private static String ownScope(String clientId) {
    return "system/Patient.cruds?resource-origin=Device/" + clientId;
  }

  private static String createPatient(IGenericClient client) {
    return client.create().resource(new Patient()).execute().getId().toUnqualifiedVersionless().getValue();
  }

  private IGenericClient client(String clientId, String scope) {
    final String token = JWT.create()
      .withClaim("azp", clientId)
      .withClaim("scope", scope)
      .sign(Algorithm.HMAC256("not-checked-in-this-test"));

    final IGenericClient client = fhirContext.newRestfulGenericClient("http://localhost:" + port + "/fhir/DEFAULT/");
    client.registerInterceptor(new BearerTokenAuthInterceptor(token));
    return client;
  }

  private static List<String> ids(Bundle bundle) {
    return bundle.getEntry().stream()
      .filter(Bundle.BundleEntryComponent::hasResource)
      .map((entry) -> entry.getResource().getIdElement().toUnqualifiedVersionless().getValue())
      .toList();
  }

  private static List<String> origins(Bundle bundle) {
    return bundle.getEntry().stream()
      .filter(Bundle.BundleEntryComponent::hasResource)
      .map((entry) -> Optional.ofNullable(entry.getResource() instanceof Patient patient
          ? patient.getExtensionByUrl(ResourceOriginUtil.RESOURCE_ORIGIN_SYSTEM) : null)
        .map(Extension::getValue)
        .map((value) -> ((Reference) value).getReference())
        .orElse("<none>"))
      .toList();
  }
}
