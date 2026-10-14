package ca.uhn.fhir.jpa.starter.koppeltaal.interceptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.starter.koppeltaal.config.SmartBackendServiceConfiguration;
import ca.uhn.fhir.rest.api.RequestTypeEnum;
import ca.uhn.fhir.rest.api.RestOperationTypeEnum;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.exceptions.ForbiddenOperationException;
import ca.uhn.fhir.rest.server.servlet.ServletRequestDetails;
import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import java.util.HashMap;
import java.util.Map;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r4.model.AuditEvent;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Device;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Task;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * The kt2-delete-flow marker as an additive grant on top of the matrix, for a caller that the matrix itself
 * gives no access to Tasks or AuditEvents: real tokens, so scope and caller are derived as in production.
 */
class DeleteFlowAuthorizationTest {

	private static final String MARKER = "http://vzvz.nl/fhir/CodeSystem/koppeltaal-security-label|kt2-delete-flow";
	private static final String NO_TASK_OR_AUDIT_RIGHTS = "system/Patient.rs";

	private final DaoRegistry daoRegistry = mock(DaoRegistry.class);
	@SuppressWarnings("unchecked")
	private final IFhirResourceDao<Device> deviceDao = mock(IFhirResourceDao.class);
	@SuppressWarnings("unchecked")
	private final IFhirResourceDao<IBaseResource> resourceDao = mock(IFhirResourceDao.class);
	private final Device caller = new Device().setStatus(Device.FHIRDeviceStatus.ACTIVE);
	private ResourceOriginAuthorizationInterceptor interceptor;

	@BeforeEach
	void setUp() {
		caller.setId("Device/me");
		when(deviceDao.read(any(IIdType.class), any(RequestDetails.class))).thenReturn(caller);
		when(daoRegistry.getResourceDao(any(String.class))).thenReturn(resourceDao);
		interceptor = new ResourceOriginAuthorizationInterceptor(daoRegistry, deviceDao,
				mock(SmartBackendServiceConfiguration.class));
	}

	@Test
	void aTaskSearchWithoutReadRightIsNarrowedToTheCallersOwnDeletePendingTasks() {
		RequestDetails search = search("Task", Map.of());

		interceptor.authorizeRequest(search);

		assertThat(search.getParameters()).containsOnlyKeys("_security", "owner");
		assertThat(search.getParameters().get("_security")).containsExactly(MARKER);
		assertThat(search.getParameters().get("owner")).containsExactly("Device/me");
	}

	@Test
	void anAuditEventSearchWithoutReadRightIsNarrowedToTheDeleteFlowEvents() {
		RequestDetails search = search("AuditEvent", Map.of());

		interceptor.authorizeRequest(search);

		assertThat(search.getParameters()).containsOnlyKeys("_security");
		assertThat(search.getParameters().get("_security")).containsExactly(MARKER);
	}

	@Test
	void theApplicationsOwnParametersStayAndAreIntersectedWithTheNarrowing() {
		RequestDetails search = search("Task", Map.of("owner", new String[] {"Device/other"}));

		interceptor.authorizeRequest(search);

		assertThat(search.getParameters().get("owner")).containsExactly("Device/other", "Device/me");
	}

	@Test
	void includesAreRefusedOnASearchOnlyTheMarkerAllows() {
		RequestDetails search = search("Task", Map.of("_include", new String[] {"Task:patient"}));

		assertThatThrownBy(() -> interceptor.authorizeRequest(search)).isInstanceOf(ForbiddenOperationException.class);
	}

	@Test
	void aTypeHistoryWithoutReadRightStaysForbidden() {
		RequestDetails history = search("Task", Map.of());
		history.setRestOperationType(RestOperationTypeEnum.HISTORY_TYPE);

		assertThatThrownBy(() -> interceptor.authorizeRequest(history)).isInstanceOf(ForbiddenOperationException.class);
	}

	@Test
	void aCallerWhoseDeviceIsNotActiveStaysForbidden() {
		caller.setStatus(Device.FHIRDeviceStatus.INACTIVE);

		assertThatThrownBy(() -> interceptor.authorizeRequest(search("Task", Map.of())))
				.isInstanceOf(ForbiddenOperationException.class);
	}

	@Test
	void otherResourceTypesWithoutReadRightStayForbidden() {
		assertThatThrownBy(() -> interceptor.authorizeRequest(search("CareTeam", Map.of())))
				.isInstanceOf(ForbiddenOperationException.class);
	}

	@Test
	void theOwnerReadsItsDeletePendingTaskWithoutReadRight() {
		stored(deletePendingTask("me"));

		interceptor.authorizeRequest(byId(RequestTypeEnum.GET, RestOperationTypeEnum.READ, "Task/dp"));
	}

	@Test
	void anotherApplicationsDeletePendingTaskStaysForbidden() {
		stored(deletePendingTask("other"));

		assertThatThrownBy(() -> interceptor.authorizeRequest(byId(RequestTypeEnum.GET, RestOperationTypeEnum.READ,
				"Task/dp"))).isInstanceOf(ForbiddenOperationException.class);
	}

	@Test
	void aDeleteFlowAuditEventIsReadableForAParticipant() {
		AuditEvent event = new AuditEvent();
		event.getMeta().addSecurity("http://vzvz.nl/fhir/CodeSystem/koppeltaal-security-label", "kt2-delete-flow", null);
		stored(event);

		interceptor.authorizeRequest(byId(RequestTypeEnum.GET, RestOperationTypeEnum.READ, "AuditEvent/archive"));
	}

	@Test
	void theOwnerMayWriteItsDeletePendingTaskWithoutUpdateRight() {
		stored(deletePendingTask("me"));

		interceptor.authorizeRequest(byId(RequestTypeEnum.PUT, RestOperationTypeEnum.UPDATE, "Task/dp"));
	}

	@Test
	void theMarkerNeverGrantsADelete() {
		stored(deletePendingTask("me"));

		assertThatThrownBy(() -> interceptor.authorizeRequest(byId(RequestTypeEnum.DELETE, RestOperationTypeEnum.DELETE,
				"Task/dp"))).isInstanceOf(ForbiddenOperationException.class);
	}

	@Test
	void anUnlabelledTaskOwnedByTheCallerStaysUnderTheMatrix() {
		Task ordinary = deletePendingTask("me");
		ordinary.getMeta().getSecurity().clear();
		stored(ordinary);

		assertThatThrownBy(() -> interceptor.authorizeRequest(byId(RequestTypeEnum.GET, RestOperationTypeEnum.READ,
				"Task/dp"))).isInstanceOf(ForbiddenOperationException.class);
	}

	private RequestDetails search(String resourceName, Map<String, String[]> parameters) {
		ServletRequestDetails request = request(RequestTypeEnum.GET, RestOperationTypeEnum.SEARCH_TYPE, resourceName);
		request.setParameters(new HashMap<>(parameters));
		return request;
	}

	private RequestDetails byId(RequestTypeEnum requestType, RestOperationTypeEnum operation, String id) {
		IdType resourceId = new IdType(id);
		ServletRequestDetails request = request(requestType, operation, resourceId.getResourceType());
		request.setId(resourceId);
		return request;
	}

	private static ServletRequestDetails request(RequestTypeEnum requestType, RestOperationTypeEnum operation,
			String resourceName) {
		ServletRequestDetails request = new ServletRequestDetails();
		request.setResourceName(resourceName);
		request.setRequestType(requestType);
		request.setRestOperationType(operation);
		MockHttpServletRequest servletRequest = new MockHttpServletRequest();
		servletRequest.addHeader("Authorization", "Bearer " + JWT.create()
				.withClaim("scope", NO_TASK_OR_AUDIT_RIGHTS)
				.withClaim("azp", "me")
				.sign(Algorithm.HMAC256("test-secret")));
		request.setServletRequest(servletRequest);
		return request;
	}

	private void stored(IBaseResource resource) {
		when(resourceDao.read(any(IIdType.class), any(RequestDetails.class), anyBoolean())).thenReturn(resource);
	}

	private static Task deletePendingTask(String ownerDevice) {
		Task task = new Task().setStatus(Task.TaskStatus.REQUESTED).setIntent(Task.TaskIntent.ORDER);
		task.setId("Task/dp");
		task.getMeta().addSecurity("http://vzvz.nl/fhir/CodeSystem/koppeltaal-security-label", "kt2-delete-flow", null);
		task.setCode(new CodeableConcept(new Coding("http://vzvz.nl/fhir/CodeSystem/koppeltaal-task-code",
				"delete-pending", null)));
		task.setOwner(new Reference("Device/" + ownerDevice));
		return task;
	}
}
