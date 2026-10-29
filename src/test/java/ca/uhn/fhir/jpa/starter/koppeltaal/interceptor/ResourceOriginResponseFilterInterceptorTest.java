package ca.uhn.fhir.jpa.starter.koppeltaal.interceptor;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.starter.koppeltaal.config.SmartBackendServiceConfiguration;
import ca.uhn.fhir.rest.api.RestOperationTypeEnum;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.api.server.ResponseDetails;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import ca.uhn.fhir.rest.server.servlet.ServletRequestDetails;
import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Device;
import org.hl7.fhir.r4.model.DomainResource;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.StringType;
import org.hl7.fhir.r4.model.Task;
import org.hl7.fhir.r4.model.ValueSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;
import java.util.stream.Collectors;

import static ca.uhn.fhir.jpa.starter.koppeltaal.util.ResourceOriginUtil.RESOURCE_ORIGIN_SYSTEM;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ResourceOriginResponseFilterInterceptorTest {

  private static final String DOMAIN_ADMIN_CLIENT_ID = "domain-admin";

  private ResourceOriginResponseFilterInterceptor interceptor;

  private IFhirResourceDao<Task> taskDao;

  private IFhirResourceDao<ValueSet> valueSetDao;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void init() {
    SmartBackendServiceConfiguration smartBackendServiceConfiguration = new SmartBackendServiceConfiguration();
    smartBackendServiceConfiguration.setDomainAdminClientId(DOMAIN_ADMIN_CLIENT_ID);

    DaoRegistry daoRegistry = mock(DaoRegistry.class);
    taskDao = mock(IFhirResourceDao.class);
    valueSetDao = mock(IFhirResourceDao.class);
    when(daoRegistry.getResourceDao("Task")).thenReturn((IFhirResourceDao) taskDao);
    when(daoRegistry.getResourceDao("ValueSet")).thenReturn((IFhirResourceDao) valueSetDao);

    interceptor = new ResourceOriginResponseFilterInterceptor(daoRegistry, smartBackendServiceConfiguration);
  }

  /**
   * The version that precedes a deletion determines the resource-origin of the deletion entry.
   */
  private void previousVersion(String versionedId, String resourceOrigin) {
    Task previousVersion = task(new IdType(versionedId).getIdPart(), resourceOrigin);
    when(taskDao.read(argThat((IIdType id) -> id != null && versionedId.equals(id.getValue())), any(RequestDetails.class), eq(true)))
      .thenReturn(previousVersion);
  }

  @Test
  void shouldRemoveTypeLevelHistoryEntriesOfOtherResourceOrigins() {
    Bundle bundle = bundle(Bundle.BundleType.HISTORY, task("1", "Device/1"), task("2", "Device/2"), task("3", "Device/3"));

    filter(bundle, "system/Task.rs?resource-origin=Device/1");

    assertEquals(List.of("Task/1"), ids(bundle));
  }

  @Test
  void shouldKeepGrantedResourceOrigins() {
    Bundle bundle = bundle(Bundle.BundleType.HISTORY, task("1", "Device/1"), task("2", "Device/2"), task("3", "Device/3"));

    filter(bundle, "system/Task.rs?resource-origin=Device/1,Device/2");

    assertEquals(List.of("Task/1", "Task/2"), ids(bundle));
  }

  @Test
  void shouldKeepEveryResourceOriginForAllScope() {
    Bundle bundle = bundle(Bundle.BundleType.SEARCHSET, task("1", "Device/1"), task("2", "Device/2"), task("3", null));

    filter(bundle, "system/Task.rs");

    assertEquals(List.of("Task/1", "Task/2", "Task/3"), ids(bundle));
  }

  @Test
  void shouldRemoveEntriesWithoutReadPermissionOnTheirResourceType() {
    Bundle bundle = bundle(Bundle.BundleType.SEARCHSET, task("1", "Device/1"), patient("1", "Device/1"));

    filter(bundle, "system/Task.rs?resource-origin=Device/1 system/Patient.c");

    assertEquals(List.of("Task/1"), ids(bundle));
  }

  @Test
  void shouldKeepDeletionEntriesOfPermittedResourceOriginsInEveryHistoryResponse() {
    previousVersion("Task/2/_history/2", "Device/1");
    previousVersion("Task/3/_history/4", "Device/2");

    for (RestOperationTypeEnum operationType : List.of(RestOperationTypeEnum.HISTORY_TYPE, RestOperationTypeEnum.HISTORY_INSTANCE, RestOperationTypeEnum.GET_PAGE)) {
      Bundle bundle = bundle(Bundle.BundleType.HISTORY);
      addDeletion(bundle, "Task/2/_history/3");
      addDeletion(bundle, "Task/3/_history/5");

      filter(bundle, "system/Task.rs?resource-origin=Device/1", operationType);

      assertEquals(List.of("Task/2/_history/3"), ids(bundle), "Should only keep the own deletion in " + operationType);
    }
  }

  @Test
  void shouldFilterInstanceHistoryVersionsOfOtherResourceOrigins() {
    // versions written before resource-origin was immutable for PATCH can have another resource-origin
    Bundle bundle = bundle(Bundle.BundleType.HISTORY,
      withVersion(task("1", "Device/2"), "3"),
      withVersion(task("1", "Device/1"), "2"),
      withVersion(task("1", "Device/2"), "1"));

    filter(bundle, "system/Task.rs?resource-origin=Device/2", RestOperationTypeEnum.HISTORY_INSTANCE);

    assertEquals(List.of("3", "1"), bundle.getEntry().stream().map((entry) -> entry.getResource().getIdElement().getVersionIdPart()).collect(Collectors.toList()));
  }

  @Test
  void shouldOnlyKeepDeletionEntriesWithUnknownResourceOriginForAllScope() {
    when(taskDao.read(any(IIdType.class), any(RequestDetails.class), eq(true))).thenThrow(new ResourceNotFoundException("gone"));

    Bundle ownBundle = bundle(Bundle.BundleType.HISTORY, task("1", "Device/1"));
    addDeletion(ownBundle, "Task/2/_history/3");
    filter(ownBundle, "system/Task.rs?resource-origin=Device/1");
    assertEquals(List.of("Task/1"), ids(ownBundle));

    Bundle allBundle = bundle(Bundle.BundleType.HISTORY, task("1", "Device/1"));
    addDeletion(allBundle, "Task/2/_history/3");
    filter(allBundle, "system/Task.rs");
    assertEquals(List.of("Task/1", "Task/2/_history/3"), ids(allBundle));
  }

  @Test
  void shouldRemoveHistoryEntryWithoutResourceAndUrl() {
    Bundle bundle = bundle(Bundle.BundleType.HISTORY);
    bundle.addEntry();

    filter(bundle, "system/*.rs");

    assertEquals(0, bundle.getEntry().size());
  }

  @Test
  void shouldKeepConformanceResourcesWithoutResourceOriginForEveryClient() {
    Bundle bundle = bundle(Bundle.BundleType.SEARCHSET, valueSet("1", null), valueSet("2", "Device/2"));

    filter(bundle, "system/Task.rs?resource-origin=Device/1");

    assertEquals(List.of("ValueSet/1"), ids(bundle));
  }

  @Test
  void shouldRemoveOtherResourcesWithoutResourceOriginForRestrictedScope() {
    Bundle bundle = bundle(Bundle.BundleType.SEARCHSET, task("1", null), task("2", "Device/1"));

    filter(bundle, "system/Task.rs?resource-origin=Device/1");

    assertEquals(List.of("Task/2"), ids(bundle));
  }

  @Test
  void shouldOnlyKeepConformanceResourcesWithoutResourceOriginWhenThereIsNoToken() {
    Bundle bundle = bundle(Bundle.BundleType.SEARCHSET, valueSet("1", null), task("1", "Device/1"), task("2", null));

    interceptor.filterResponse(requestDetailsWithoutToken(), new ResponseDetails(bundle));

    assertEquals(List.of("ValueSet/1"), ids(bundle));
  }

  @Test
  void shouldIgnoreATokenThatWasNotValidated() {
    // e.g. sent along with a request on a path where the JwtSecurityInterceptor does not require a valid token
    String forgedToken = JWT.create().withClaim("scope", "system/*.rs").sign(Algorithm.HMAC256("forged"));
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("Authorization", "Bearer " + forgedToken);
    ServletRequestDetails requestDetails = new ServletRequestDetails();
    requestDetails.setServletRequest(request);
    requestDetails.setRestOperationType(RestOperationTypeEnum.SEARCH_TYPE);

    Bundle bundle = bundle(Bundle.BundleType.SEARCHSET, valueSet("1", null), task("1", "Device/1"));
    interceptor.filterResponse(requestDetails, new ResponseDetails(bundle));

    assertEquals(List.of("ValueSet/1"), ids(bundle));
  }

  @Test
  void shouldOnlyKeepDeletionsOfConformanceResourcesWhenThePreviousVersionCanBeRead() {
    when(valueSetDao.read(any(IIdType.class), any(RequestDetails.class), eq(true))).thenThrow(new ResourceNotFoundException("gone"));

    Bundle bundle = bundle(Bundle.BundleType.HISTORY);
    addDeletion(bundle, "ValueSet/1/_history/3");

    filter(bundle, "system/Task.rs?resource-origin=Device/1", RestOperationTypeEnum.HISTORY_TYPE);

    assertEquals(List.of(), ids(bundle));
  }

  @Test
  void shouldKeepOperationOutcomeEntries() {
    Bundle bundle = bundle(Bundle.BundleType.SEARCHSET, task("1", "Device/2"));
    OperationOutcome outcome = new OperationOutcome();
    outcome.setId("OperationOutcome/outcome");
    bundle.addEntry().setResource(outcome).getSearch().setMode(Bundle.SearchEntryMode.OUTCOME);

    filter(bundle, "system/Task.rs?resource-origin=Device/1");

    assertEquals(List.of("OperationOutcome/outcome"), ids(bundle));
  }

  @Test
  void shouldFilterStoredOperationOutcomesLikeAnyOtherResource() {
    // OperationOutcome is a storable type on this server, only the outcome of the search itself is kept
    Bundle bundle = bundle(Bundle.BundleType.HISTORY, withResourceOrigin(new OperationOutcome(), "OperationOutcome/stored", "Device/2"));

    filter(bundle, "system/OperationOutcome.rs?resource-origin=Device/1");

    assertEquals(List.of(), ids(bundle));
  }

  @Test
  void shouldRemoveResourcesWithAnInvalidResourceOriginInsteadOfFailingTheResponse() {
    Task twoResourceOrigins = task("1", "Device/1");
    twoResourceOrigins.addExtension(new Extension(RESOURCE_ORIGIN_SYSTEM, new Reference("Device/2")));
    Task noReference = task("2", null);
    noReference.addExtension(new Extension(RESOURCE_ORIGIN_SYSTEM, new StringType("Device/1")));

    Bundle bundle = bundle(Bundle.BundleType.SEARCHSET, twoResourceOrigins, noReference, task("3", "Device/1"));

    filter(bundle, "system/Task.rs?resource-origin=Device/1");

    assertEquals(List.of("Task/3"), ids(bundle));
  }

  @Test
  void shouldKeepDeletionsOfConformanceResourcesWithoutResourceOrigin() {
    when(valueSetDao.read(argThat((IIdType id) -> id != null && "ValueSet/1/_history/2".equals(id.getValue())), any(RequestDetails.class), eq(true)))
      .thenReturn(valueSet("1", null));

    Bundle bundle = bundle(Bundle.BundleType.HISTORY);
    addDeletion(bundle, "ValueSet/1/_history/3");

    filter(bundle, "system/Task.rs?resource-origin=Device/1", RestOperationTypeEnum.HISTORY_TYPE);

    assertEquals(List.of("ValueSet/1/_history/3"), ids(bundle));
  }

  @Test
  void shouldKeepDevicesForDomainAdmin() {
    Bundle bundle = bundle(Bundle.BundleType.SEARCHSET, device("1", null), device("2", "Device/1"));

    interceptor.filterResponse(requestDetails("system/Task.rs?resource-origin=Device/1", DOMAIN_ADMIN_CLIENT_ID), new ResponseDetails(bundle));

    assertEquals(List.of("Device/1", "Device/2"), ids(bundle));
  }

  @Test
  void shouldNotKeepDevicesForOtherClients() {
    Bundle bundle = bundle(Bundle.BundleType.SEARCHSET, device("1", null), device("2", "Device/1"), device("3", "Device/2"));

    filter(bundle, "system/Device.rs?resource-origin=Device/1");

    assertEquals(List.of("Device/2"), ids(bundle));
  }

  @Test
  void shouldFilterPagesRegardlessOfTheRequestedBundleType() {
    // the client chooses the Bundle type of a page with the _bundletype parameter
    for (Bundle.BundleType bundleType : List.of(Bundle.BundleType.COLLECTION, Bundle.BundleType.BATCHRESPONSE, Bundle.BundleType.HISTORY)) {
      Bundle page = bundle(bundleType, task("1", "Device/1"), task("2", "Device/2"));

      filter(page, "system/Task.rs?resource-origin=Device/1", RestOperationTypeEnum.GET_PAGE);

      assertEquals(List.of("Task/1"), ids(page), "Should filter a " + bundleType + " page");
    }
  }

  @Test
  void shouldFilterSearchesRegardlessOfTheBundleType() {
    Bundle bundle = bundle(Bundle.BundleType.COLLECTION, task("1", "Device/1"), task("2", "Device/2"));

    filter(bundle, "system/Task.rs?resource-origin=Device/1", RestOperationTypeEnum.SEARCH_TYPE);

    assertEquals(List.of("Task/1"), ids(bundle));
  }

  @Test
  void shouldNotTouchOtherResponses() {
    Task task = task("1", "Device/2");
    ResponseDetails responseDetails = new ResponseDetails(task);

    interceptor.filterResponse(requestDetails("system/Task.rs?resource-origin=Device/1", "client", RestOperationTypeEnum.READ), responseDetails);
    assertSame(task, responseDetails.getResponseResource());

    // a stored Bundle resource is content, not a result set
    Bundle storedBundle = bundle(Bundle.BundleType.COLLECTION, task("2", "Device/2"));
    filter(storedBundle, "system/Bundle.rs system/Task.rs?resource-origin=Device/1", RestOperationTypeEnum.READ);
    assertEquals(List.of("Task/2"), ids(storedBundle));
  }

  private void filter(Bundle bundle, String scope) {
    RestOperationTypeEnum operationType = bundle.getType() == Bundle.BundleType.HISTORY
      ? RestOperationTypeEnum.HISTORY_TYPE
      : RestOperationTypeEnum.SEARCH_TYPE;

    filter(bundle, scope, operationType);
  }

  private void filter(Bundle bundle, String scope, RestOperationTypeEnum operationType) {
    interceptor.filterResponse(requestDetails(scope, "client", operationType), new ResponseDetails(bundle));
  }

  private static RequestDetails requestDetails(String scope, String clientId) {
    return requestDetails(scope, clientId, RestOperationTypeEnum.SEARCH_TYPE);
  }

  private static RequestDetails requestDetails(String scope, String clientId, RestOperationTypeEnum operationType) {
    String token = JWT.create()
      .withClaim("scope", scope)
      .withClaim("azp", clientId)
      .sign(Algorithm.HMAC256("super-secret"));

    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("Authorization", "Bearer " + token);
    request.setAttribute(JwtSecurityInterceptor.VALIDATED_ACCESS_TOKEN_ATTRIBUTE, token);

    ServletRequestDetails requestDetails = new ServletRequestDetails();
    requestDetails.setServletRequest(request);
    requestDetails.setRestOperationType(operationType);
    return requestDetails;
  }

  private static RequestDetails requestDetailsWithoutToken() {
    ServletRequestDetails requestDetails = new ServletRequestDetails();
    requestDetails.setServletRequest(new MockHttpServletRequest());
    requestDetails.setRestOperationType(RestOperationTypeEnum.SEARCH_TYPE);
    return requestDetails;
  }

  private static Bundle bundle(Bundle.BundleType type, Resource... resources) {
    Bundle bundle = new Bundle();
    bundle.setType(type);
    for (Resource resource : resources) {
      bundle.addEntry().setResource(resource);
    }
    return bundle;
  }

  private static void addDeletion(Bundle bundle, String url) {
    bundle.addEntry().getRequest().setMethod(Bundle.HTTPVerb.DELETE).setUrl(url);
  }

  private static List<String> ids(Bundle bundle) {
    return bundle.getEntry().stream()
      .map((entry) -> entry.getResource() != null
        ? entry.getResource().getIdElement().toUnqualifiedVersionless().getValue()
        : entry.getRequest().getUrl())
      .collect(Collectors.toList());
  }

  private static Task task(String id, String resourceOrigin) {
    return withResourceOrigin(new Task(), "Task/" + id, resourceOrigin);
  }

  private static Patient patient(String id, String resourceOrigin) {
    return withResourceOrigin(new Patient(), "Patient/" + id, resourceOrigin);
  }

  private static ValueSet valueSet(String id, String resourceOrigin) {
    return withResourceOrigin(new ValueSet(), "ValueSet/" + id, resourceOrigin);
  }

  private static Device device(String id, String resourceOrigin) {
    return withResourceOrigin(new Device(), "Device/" + id, resourceOrigin);
  }

  private static <T extends DomainResource> T withVersion(T resource, String version) {
    resource.setIdElement(resource.getIdElement().withVersion(version));
    return resource;
  }

  private static <T extends DomainResource> T withResourceOrigin(T resource, String id, String resourceOrigin) {
    resource.setId(id);
    if (resourceOrigin != null) {
      resource.addExtension(new Extension(RESOURCE_ORIGIN_SYSTEM, new Reference(resourceOrigin)));
    }
    return resource;
  }
}
