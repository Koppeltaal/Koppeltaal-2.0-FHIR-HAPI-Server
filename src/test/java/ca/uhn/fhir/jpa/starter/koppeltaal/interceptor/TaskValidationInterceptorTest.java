package ca.uhn.fhir.jpa.starter.koppeltaal.interceptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.api.RestOperationTypeEnum;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import ca.uhn.fhir.rest.server.servlet.ServletRequestDetails;
import ca.uhn.fhir.rest.server.exceptions.ForbiddenOperationException;
import ca.uhn.fhir.rest.server.exceptions.UnprocessableEntityException;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Date;

import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.DateTimeType;
import org.hl7.fhir.r4.model.Period;
import org.hl7.fhir.r4.model.Task;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockHttpServletRequest;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TaskValidationInterceptorTest {

    private static final String OWNER = "app-device-1";
    private static final String TASK_CODE_SYSTEM = "http://vzvz.nl/fhir/CodeSystem/koppeltaal-task-code";
    private static final Date AUTHORED_ON = Date.from(java.time.Instant.parse("2026-07-01T06:00:00Z"));
    private static final Date GRACE_END = Date.from(java.time.Instant.parse("2026-07-31T06:00:00Z"));

    private static final FhirContext FHIR_CONTEXT = FhirContext.forR4();

    private TaskValidationInterceptor interceptor;

    @Mock
    private RequestDetails requestDetails;

    @BeforeEach
    void setUp() {
        interceptor = new TaskValidationInterceptor(FHIR_CONTEXT);
    }

    // ---------------------------------------------------------------- helpers

    private static Task deleteFlowTask(Task.TaskStatus status) {
        Task task = new Task();
        task.setId("Task/task-1");
        task.getMeta().addSecurity()
                .setSystem("http://vzvz.nl/fhir/CodeSystem/koppeltaal-security-label")
                .setCode("kt2-delete-flow");
        task.getCode().addCoding().setSystem(TASK_CODE_SYSTEM).setCode("delete-pending");
        task.setStatus(status);
        task.setIntent(Task.TaskIntent.ORDER);
        task.getFor().setReference("Patient/p1");
        task.getOwner().setReference("Device/" + OWNER);
        task.getRequester().setReference("Device/server-dev");
        task.setAuthoredOn(AUTHORED_ON);
        task.setRestriction(new Task.TaskRestrictionComponent().setPeriod(new Period().setEnd(GRACE_END)));
        return task;
    }

    private static String accessTokenFor(String clientId) {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        String header = encoder.encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8));
        String payload = encoder.encodeToString(
                ("{\"azp\":\"" + clientId + "\"}").getBytes(StandardCharsets.UTF_8));
        return header + "." + payload + ".";
    }

    /** The request as the JwtSecurityInterceptor leaves it, having validated the caller's access token. */
    private void callerIs(String clientId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(JwtSecurityInterceptor.VALIDATED_ACCESS_TOKEN_ATTRIBUTE, accessTokenFor(clientId));
        ServletRequestDetails servletRequestDetails = new ServletRequestDetails();
        servletRequestDetails.setServletRequest(request);
        requestDetails = servletRequestDetails;
    }

    /** A legitimate response by the owning application: only the status moves. */
    private void update(Task stored, Task incoming) {
        interceptor.resourceUpdating(stored, incoming, requestDetails);
    }

    private static Task respondWith(Task.TaskStatus status) {
        Task incoming = deleteFlowTask(Task.TaskStatus.REQUESTED).copy();
        incoming.setId("Task/task-1");
        incoming.setStatus(status);
        return incoming;
    }

    // ------------------------------------------------------- status transitions

    @Test
    void updateToOnHoldIsAllowed() {
        assertDoesNotThrow(() -> update(deleteFlowTask(Task.TaskStatus.REQUESTED),
                respondWith(Task.TaskStatus.ONHOLD)));
    }

    @Test
    void updateToAcceptedIsAllowed() {
        assertDoesNotThrow(() -> update(deleteFlowTask(Task.TaskStatus.ONHOLD),
                respondWith(Task.TaskStatus.ACCEPTED)));
    }

    @Test
    void updateToCancelledIsRejected() {
        UnprocessableEntityException exception = assertThrows(UnprocessableEntityException.class,
                () -> update(deleteFlowTask(Task.TaskStatus.REQUESTED), respondWith(Task.TaskStatus.CANCELLED)));
        assertEquals(
                "A Task with the 'kt2-delete-flow' security label can only be updated to status 'on-hold' or 'accepted', but was 'cancelled'",
                exception.getMessage());
    }

    @Test
    void updateToCompletedIsRejected() {
        assertThrows(UnprocessableEntityException.class,
                () -> update(deleteFlowTask(Task.TaskStatus.REQUESTED), respondWith(Task.TaskStatus.COMPLETED)));
    }

    @Test
    void updateToRequestedIsRejected() {
        assertThrows(UnprocessableEntityException.class,
                () -> update(deleteFlowTask(Task.TaskStatus.ONHOLD), respondWith(Task.TaskStatus.REQUESTED)));
    }

    @Test
    void updateWithoutStatusIsRejected() {
        Task incoming = respondWith(Task.TaskStatus.ONHOLD);
        incoming.setStatus(null);

        UnprocessableEntityException exception = assertThrows(UnprocessableEntityException.class,
                () -> update(deleteFlowTask(Task.TaskStatus.REQUESTED), incoming));
        assertEquals(
                "A Task with the 'kt2-delete-flow' security label can only be updated to status 'on-hold' or 'accepted', but was '<missing>'",
                exception.getMessage());
    }

    // -------------------------------------------------------------- ownership

    @Test
    void updateByOwningApplicationIsAllowed() {
        callerIs(OWNER);

        assertDoesNotThrow(() -> update(deleteFlowTask(Task.TaskStatus.REQUESTED),
                respondWith(Task.TaskStatus.ONHOLD)));
    }

    @Test
    void updateByOtherApplicationIsForbidden() {
        callerIs("another-app-device");

        ForbiddenOperationException exception = assertThrows(ForbiddenOperationException.class,
                () -> update(deleteFlowTask(Task.TaskStatus.REQUESTED), respondWith(Task.TaskStatus.ONHOLD)));
        assertEquals("A Task with the 'kt2-delete-flow' security label can only be updated by the application that owns it",
                exception.getMessage());
    }

    @Test
    void ownershipIsJudgedOnTheStoredTaskNotOnThePayload() {
        // The caller claims the Task by rewriting owner in the body it sends. The
        // message matters: judged on the payload the owner check would be satisfied
        // and only the field comparison would object.
        Task incoming = respondWith(Task.TaskStatus.ONHOLD);
        incoming.getOwner().setReference("Device/another-app-device");
        callerIs("another-app-device");

        ForbiddenOperationException exception = assertThrows(ForbiddenOperationException.class,
                () -> update(deleteFlowTask(Task.TaskStatus.REQUESTED), incoming));
        assertEquals("A Task with the 'kt2-delete-flow' security label can only be updated by the application that owns it",
                exception.getMessage());
    }

    /** With security disabled there is no validated access token, and so no owner to compare with. */
    @Test
    void ownerIsNotCheckedWithoutAValidatedAccessToken() {
        assertDoesNotThrow(() -> update(deleteFlowTask(Task.TaskStatus.REQUESTED),
                respondWith(Task.TaskStatus.ONHOLD)));
    }

    // ------------------------------------------------------ server-owned fields

    @Test
    void droppingTheDeleteFlowMarkerIsForbidden() {
        Task incoming = respondWith(Task.TaskStatus.ONHOLD);
        incoming.getMeta().getSecurity().clear();

        ForbiddenOperationException exception = assertThrows(ForbiddenOperationException.class,
                () -> update(deleteFlowTask(Task.TaskStatus.REQUESTED), incoming));
        assertTrue(exception.getMessage().endsWith("Task.meta.security[0]"), exception.getMessage());
    }

    @Test
    void changingTheProfileClaimIsRefused() {
        Task incoming = respondWith(Task.TaskStatus.ONHOLD);
        incoming.getMeta().getProfile().clear();
        incoming.getMeta().addProfile("http://koppeltaal.nl/fhir/StructureDefinition/KT2Task");

        ForbiddenOperationException exception = assertThrows(ForbiddenOperationException.class,
                () -> update(deleteFlowTask(Task.TaskStatus.REQUESTED), incoming));
        assertTrue(exception.getMessage().contains("Task.meta.profile"), exception.getMessage());
    }

    @Test
    void rewritingATimestampIsRefusedEvenForTheSameMoment() {
        // Comparing moments rather than what is written down let a different
        // sub-second precision, an element id or a primitive extension through.
        Task stored = deleteFlowTask(Task.TaskStatus.REQUESTED);
        stored.setAuthoredOnElement(new DateTimeType("2026-07-01T06:00:00+00:00"));

        Task incoming = respondWith(Task.TaskStatus.ONHOLD);
        incoming.setAuthoredOnElement(new DateTimeType("2026-07-01T08:00:00+02:00"));

        ForbiddenOperationException exception = assertThrows(ForbiddenOperationException.class,
                () -> update(stored, incoming));
        assertTrue(exception.getMessage().endsWith("Task.authoredOn"), exception.getMessage());
    }

    @Test
    void anEmptyElementLeftBehindByAnotherInterceptorIsNotAChange() {
        // Reading a field creates an empty element that FHIR considers absent but
        // the differ still sees; the repository validator runs before this one.
        Task incoming = respondWith(Task.TaskStatus.ONHOLD);
        incoming.getText();
        incoming.getNote();
        incoming.getInsurance();

        assertDoesNotThrow(() -> update(deleteFlowTask(Task.TaskStatus.REQUESTED), incoming));
    }

    @Test
    void addingADisplayToTheTaskCodeIsRefused() {
        Task incoming = respondWith(Task.TaskStatus.ONHOLD);
        incoming.getCode().getCodingFirstRep().setDisplay("Iets anders");

        ForbiddenOperationException exception = assertThrows(ForbiddenOperationException.class,
                () -> update(deleteFlowTask(Task.TaskStatus.REQUESTED), incoming));
        assertTrue(exception.getMessage().endsWith("Task.code.coding[0].display"), exception.getMessage());
    }

    @Test
    void theStatusAndItsReasonAreTheOnlyThingsAnApplicationWrites() {
        Task incoming = respondWith(Task.TaskStatus.ONHOLD);
        incoming.setStatusReason(new CodeableConcept().addCoding(
                new Coding("http://vzvz.nl/fhir/CodeSystem/koppeltaal-delete-hold-reason", "data-export-pending", null)));

        assertDoesNotThrow(() -> update(deleteFlowTask(Task.TaskStatus.REQUESTED), incoming));
    }

    @Test
    void changingTheOwnerIsRefused() {
        Task incoming = respondWith(Task.TaskStatus.ONHOLD);
        incoming.getOwner().setReference("Device/another-app-device");

        ForbiddenOperationException exception = assertThrows(ForbiddenOperationException.class,
                () -> update(deleteFlowTask(Task.TaskStatus.REQUESTED), incoming));
        assertTrue(exception.getMessage().endsWith("Task.owner.reference"), exception.getMessage());
    }

    @Test
    void changingThePatientIsRefused() {
        Task incoming = respondWith(Task.TaskStatus.ONHOLD);
        incoming.getFor().setReference("Patient/someone-else");

        ForbiddenOperationException exception = assertThrows(ForbiddenOperationException.class,
                () -> update(deleteFlowTask(Task.TaskStatus.REQUESTED), incoming));
        assertTrue(exception.getMessage().endsWith("Task.for.reference"), exception.getMessage());
    }

    @Test
    void changingTheRequesterIsRefused() {
        Task incoming = respondWith(Task.TaskStatus.ONHOLD);
        incoming.getRequester().setReference("Device/" + OWNER);

        ForbiddenOperationException exception = assertThrows(ForbiddenOperationException.class,
                () -> update(deleteFlowTask(Task.TaskStatus.REQUESTED), incoming));
        assertTrue(exception.getMessage().endsWith("Task.requester.reference"), exception.getMessage());
    }

    @Test
    void droppingTheTaskCodeIsRefused() {
        Task incoming = respondWith(Task.TaskStatus.ONHOLD);
        incoming.setCode(null);

        ForbiddenOperationException exception = assertThrows(ForbiddenOperationException.class,
                () -> update(deleteFlowTask(Task.TaskStatus.REQUESTED), incoming));
        assertTrue(exception.getMessage().endsWith("Task.code"), exception.getMessage());
    }

    @Test
    void movingTheGraceDeadlineIsRefused() {
        Task incoming = respondWith(Task.TaskStatus.ONHOLD);
        incoming.getRestriction().getPeriod().setEnd(Date.from(java.time.Instant.parse("2099-01-01T00:00:00Z")));

        ForbiddenOperationException exception = assertThrows(ForbiddenOperationException.class,
                () -> update(deleteFlowTask(Task.TaskStatus.REQUESTED), incoming));
        assertTrue(exception.getMessage().endsWith("Task.restriction.period.end"), exception.getMessage());
    }

    @Test
    void restartingTheGraceByMovingAuthoredOnIsRefused() {
        Task incoming = respondWith(Task.TaskStatus.ONHOLD);
        incoming.setAuthoredOn(Date.from(java.time.Instant.parse("2026-09-01T00:00:00Z")));

        ForbiddenOperationException exception = assertThrows(ForbiddenOperationException.class,
                () -> update(deleteFlowTask(Task.TaskStatus.REQUESTED), incoming));
        assertTrue(exception.getMessage().endsWith("Task.authoredOn"), exception.getMessage());
    }

    @Test
    void aFieldTheRulesNeverNamedIsRefusedToo() {
        // The point of comparing against the expected response: it also covers what
        // no list of forbidden fields mentions.
        Task incoming = respondWith(Task.TaskStatus.ONHOLD);
        incoming.addIdentifier().setSystem("http://example.org").setValue("smuggled");
        incoming.setPriority(Task.TaskPriority.STAT);
        incoming.setDescription("smuggled");

        ForbiddenOperationException exception = assertThrows(ForbiddenOperationException.class,
                () -> update(deleteFlowTask(Task.TaskStatus.REQUESTED), incoming));
        assertTrue(exception.getMessage().endsWith("Task.identifier, Task.priority, Task.description"),
                exception.getMessage());
    }

    // ------------------------------------------------------------------ scope

    @Test
    void serverWritesAreNotSubjectToTheDeleteFlowRules() {
        assertDoesNotThrow(() -> interceptor.resourceUpdating(deleteFlowTask(Task.TaskStatus.ONHOLD),
                respondWith(Task.TaskStatus.CANCELLED), new SystemRequestDetails()));
    }

    @Test
    void aRegularTaskIsNotSubjectToTheDeleteFlowRules() {
        Task stored = new Task();
        stored.setStatus(Task.TaskStatus.REQUESTED);
        Task incoming = new Task();
        incoming.setStatus(Task.TaskStatus.CANCELLED);

        assertDoesNotThrow(() -> update(stored, incoming));
    }

    @Test
    void addingTheDeleteFlowMarkerToAnOrdinaryTaskIsForbidden() {
        Task stored = new Task();
        stored.setStatus(Task.TaskStatus.REQUESTED);
        Task incoming = deleteFlowTask(Task.TaskStatus.ONHOLD);

        ForbiddenOperationException exception = assertThrows(ForbiddenOperationException.class,
                () -> update(stored, incoming));
        assertEquals("An update that adds the server-owned 'kt2-delete-flow' security label is refused",
                exception.getMessage());
    }

    @Test
    void creatingADeleteFlowTaskIsRejected() {
        UnprocessableEntityException exception = assertThrows(UnprocessableEntityException.class,
                () -> interceptor.resourceCreating(deleteFlowTask(Task.TaskStatus.REQUESTED), requestDetails));
        assertEquals("A Task with the 'kt2-delete-flow' security label is created by the Koppeltaalvoorziening only",
                exception.getMessage());
    }

    @Test
    void creatingATaskThatOnlyCarriesTheMarkerIsRejected() {
        // A client writes the create payload, so the code cannot be assumed: an
        // application may not mint resources carrying the marker either.
        Task task = deleteFlowTask(Task.TaskStatus.REQUESTED);
        task.setCode(null);

        assertThrows(UnprocessableEntityException.class,
                () -> interceptor.resourceCreating(task, requestDetails));
    }

    @Test
    void theServerMayCreateADeleteFlowTask() {
        assertDoesNotThrow(() -> interceptor.resourceCreating(deleteFlowTask(Task.TaskStatus.REQUESTED),
                new SystemRequestDetails()));
    }

    @Test
    void creatingARegularTaskIsAllowed() {
        Task task = new Task();
        task.setStatus(Task.TaskStatus.REQUESTED);

        assertDoesNotThrow(() -> interceptor.resourceCreating(task, requestDetails));
    }

    // --------------------------------------------------------------- sub tasks

    @Test
    void subTaskWithPartOfIsAllowed() {
        Task task = new Task();
        task.addPartOf().setReference("Task/123");

        when(requestDetails.getResource()).thenReturn(task);
        when(requestDetails.getRestOperationType()).thenReturn(RestOperationTypeEnum.CREATE);

        assertDoesNotThrow(() -> interceptor.incomingRequestPreHandled(requestDetails));
    }

    @Test
    void subTaskWithoutPartOfIsRejected() {
        Task task = new Task();
        task.getCode().addCoding().setSystem(TASK_CODE_SYSTEM).setCode("view");

        when(requestDetails.getResource()).thenReturn(task);
        when(requestDetails.getRestOperationType()).thenReturn(RestOperationTypeEnum.CREATE);

        UnprocessableEntityException exception = assertThrows(UnprocessableEntityException.class,
                () -> interceptor.incomingRequestPreHandled(requestDetails));
        assertEquals("Task with code 'view' should have a 'partOf' reference", exception.getMessage());
    }

    @Test
    void subTaskRuleAlsoAppliesOnUpdate() {
        Task task = new Task();
        task.getCode().addCoding().setSystem(TASK_CODE_SYSTEM).setCode("view");

        when(requestDetails.getResource()).thenReturn(task);
        when(requestDetails.getRestOperationType()).thenReturn(RestOperationTypeEnum.UPDATE);

        assertThrows(UnprocessableEntityException.class,
                () -> interceptor.incomingRequestPreHandled(requestDetails));
    }

    @Test
    void aDifferentTaskCodeNeedsNoPartOf() {
        Task task = new Task();
        task.getCode().addCoding().setSystem(TASK_CODE_SYSTEM).setCode("differentCode");

        when(requestDetails.getResource()).thenReturn(task);
        when(requestDetails.getRestOperationType()).thenReturn(RestOperationTypeEnum.CREATE);

        assertDoesNotThrow(() -> interceptor.incomingRequestPreHandled(requestDetails));
    }

    @Test
    void aSubtaskCodeInAnotherSystemNeedsNoPartOf() {
        Task task = new Task();
        task.getCode().addCoding().setSystem("http://different-system.com").setCode("view");

        when(requestDetails.getResource()).thenReturn(task);
        when(requestDetails.getRestOperationType()).thenReturn(RestOperationTypeEnum.CREATE);

        assertDoesNotThrow(() -> interceptor.incomingRequestPreHandled(requestDetails));
    }

    @Test
    void aNonTaskResourceIsIgnored() {
        when(requestDetails.getResource()).thenReturn(null);
        when(requestDetails.getRestOperationType()).thenReturn(RestOperationTypeEnum.CREATE);

        assertDoesNotThrow(() -> interceptor.incomingRequestPreHandled(requestDetails));
    }

    @Test
    void operationsOtherThanCreateAndUpdateAreIgnored() {
        Task task = new Task();
        task.getCode().addCoding().setSystem(TASK_CODE_SYSTEM).setCode("view");

        when(requestDetails.getResource()).thenReturn(task);

        for (RestOperationTypeEnum operationType : RestOperationTypeEnum.values()) {
            if (operationType != RestOperationTypeEnum.CREATE && operationType != RestOperationTypeEnum.UPDATE) {
                when(requestDetails.getRestOperationType()).thenReturn(operationType);
                assertDoesNotThrow(() -> interceptor.incomingRequestPreHandled(requestDetails));
            }
        }
    }
}
