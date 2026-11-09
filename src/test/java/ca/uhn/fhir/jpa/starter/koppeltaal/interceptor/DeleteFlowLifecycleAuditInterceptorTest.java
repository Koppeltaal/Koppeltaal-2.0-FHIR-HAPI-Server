package ca.uhn.fhir.jpa.starter.koppeltaal.interceptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.jpa.starter.koppeltaal.cleanup.AnnouncementFactory;
import ca.uhn.fhir.jpa.starter.koppeltaal.cleanup.CleanupProperties;
import ca.uhn.fhir.jpa.starter.koppeltaal.cleanup.DeleteFlowAggregateLock;
import ca.uhn.fhir.jpa.starter.koppeltaal.cleanup.ParticipatingApps;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import ca.uhn.fhir.rest.param.ReferenceParam;
import ca.uhn.fhir.rest.param.TokenParam;
import ca.uhn.fhir.rest.server.SimpleBundleProvider;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.AuditEvent;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.Task;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DeleteFlowLifecycleAuditInterceptorTest {

    private static final String PATIENT_ID = "p1";
    private static final String UPDATED_TASK_ID = "task-1";

    @Mock private DaoRegistry daoRegistry;
    @Mock private IFhirResourceDao<Task> taskDao;
    @Mock private IFhirResourceDao<AuditEvent> auditEventDao;
    @Mock private ParticipatingApps participatingApps;
    @Mock private DeleteFlowAggregateLock aggregateLock;
    @Mock private RequestDetails requestDetails;
    @Mock private PlatformTransactionManager transactionManager;

    /** The Tasks the repository holds for this patient, besides the updated one. */
    private final List<Task> storedSiblings = new ArrayList<>();

    private DeleteFlowLifecycleAuditInterceptor interceptor;

    @BeforeEach
    void setUp() {
        when(daoRegistry.getResourceDao(Task.class)).thenReturn(taskDao);
        when(daoRegistry.getResourceDao(AuditEvent.class)).thenReturn(auditEventDao);
        when(participatingApps.serverDeviceId()).thenReturn(new IdType("Device", "server-dev"));

        // Answers the way the repository would: only when the query actually selects
        // this patient's delete-pending Tasks, and including the updated Task itself
        // in its new state - the interceptor has to exclude that one on its own.
        when(taskDao.search(any(SearchParameterMap.class), any())).thenAnswer(invocation -> {
            SearchParameterMap query = invocation.getArgument(0);
            if (!selectsDeletePendingTasksOf(query, PATIENT_ID)) {
                return new SimpleBundleProvider(List.of());
            }
            List<IBaseResource> result = new ArrayList<>(storedSiblings);
            result.add(lastUpdatedTask);
            return new SimpleBundleProvider(result);
        });

        AnnouncementFactory factory = new AnnouncementFactory(new CleanupProperties(),
                Clock.fixed(Instant.parse("2026-08-21T12:00:00Z"), ZoneOffset.UTC));
        interceptor = new DeleteFlowLifecycleAuditInterceptor(daoRegistry, factory, participatingApps,
                aggregateLock, transactionManager, "Koppeltaal domein VZVZ");
    }

    private static boolean selectsDeletePendingTasksOf(SearchParameterMap query, String patientIdPart) {
        return hasParam(query, Task.SP_PATIENT, "Patient/" + patientIdPart)
                && hasParam(query, Task.SP_CODE, "delete-pending");
    }

    private static boolean hasParam(SearchParameterMap query, String name, String expectedValue) {
        if (query.get(name) == null) {
            return false;
        }
        return query.get(name).stream().flatMap(List::stream).anyMatch(param -> {
            if (param instanceof ReferenceParam) {
                return expectedValue.equals(((ReferenceParam) param).getValue());
            }
            return param instanceof TokenParam && expectedValue.equals(((TokenParam) param).getValue());
        });
    }

    private Task lastUpdatedTask;

    private void withSiblings(Task... tasks) {
        storedSiblings.clear();
        storedSiblings.addAll(List.of(tasks));
    }

    private static Task deleteFlowTask(String id, Task.TaskStatus status) {
        Task task = new Task();
        task.setId("Task/" + id);
        task.getMeta().addSecurity()
                .setSystem("http://vzvz.nl/fhir/CodeSystem/koppeltaal-security-label")
                .setCode("kt2-delete-flow");
        task.getCode().addCoding().setSystem("http://vzvz.nl/fhir/CodeSystem/koppeltaal-task-code")
                .setCode("delete-pending");
        task.getFor().setReference("Patient/" + PATIENT_ID);
        task.setStatus(status);
        return task;
    }

    private void statusChange(Task.TaskStatus previous, Task.TaskStatus current) {
        lastUpdatedTask = deleteFlowTask(UPDATED_TASK_ID, current);
        interceptor.resourceUpdated(deleteFlowTask(UPDATED_TASK_ID, previous), lastUpdatedTask, requestDetails);
    }

    private AuditEvent capturedEvent() {
        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditEventDao).create(captor.capture(), any(RequestDetails.class));
        return captor.getValue();
    }

    private void assertNoEventRecorded() {
        verify(auditEventDao, never()).create(any(AuditEvent.class), any(RequestDetails.class));
    }

    @Test
    void firstHandbrakeRecordsHold() {
        withSiblings(deleteFlowTask("task-2", Task.TaskStatus.REQUESTED));

        statusChange(Task.TaskStatus.REQUESTED, Task.TaskStatus.ONHOLD);

        AuditEvent event = capturedEvent();
        assertEquals("hold", event.getType().getCode());
        assertEquals("http://terminology.hl7.org/CodeSystem/iso-21089-lifecycle", event.getType().getSystem());
        assertEquals(AuditEvent.AuditEventAction.U, event.getAction());
    }

    @Test
    void secondHandbrakeRecordsNothing() {
        withSiblings(deleteFlowTask("task-2", Task.TaskStatus.ONHOLD));

        statusChange(Task.TaskStatus.REQUESTED, Task.TaskStatus.ONHOLD);

        assertNoEventRecorded();
    }

    @Test
    void releaseWhileAnotherApplicationHoldsRecordsNothing() {
        withSiblings(deleteFlowTask("task-2", Task.TaskStatus.ONHOLD));

        statusChange(Task.TaskStatus.ONHOLD, Task.TaskStatus.ACCEPTED);

        assertNoEventRecorded();
    }

    @Test
    void lastHandbrakeReleasedRecordsUnhold() {
        withSiblings(deleteFlowTask("task-2", Task.TaskStatus.REQUESTED));

        statusChange(Task.TaskStatus.ONHOLD, Task.TaskStatus.ACCEPTED);

        AuditEvent event = capturedEvent();
        assertEquals("unhold", event.getType().getCode());
        assertEquals(AuditEvent.AuditEventAction.U, event.getAction());
    }

    @Test
    void lastHandbrakeReleasedWithEverythingAcceptedRecordsNothing() {
        withSiblings(deleteFlowTask("task-2", Task.TaskStatus.ACCEPTED));

        statusChange(Task.TaskStatus.ONHOLD, Task.TaskStatus.ACCEPTED);

        assertNoEventRecorded();
    }

    @Test
    void soleApplicationReleasingItsHandbrakeReachesEarlyCompletion() {
        withSiblings();

        statusChange(Task.TaskStatus.ONHOLD, Task.TaskStatus.ACCEPTED);

        assertNoEventRecorded();
    }

    @Test
    void acceptedWithoutPrecedingHoldRecordsNothing() {
        withSiblings(deleteFlowTask("task-2", Task.TaskStatus.REQUESTED));

        statusChange(Task.TaskStatus.REQUESTED, Task.TaskStatus.ACCEPTED);

        assertNoEventRecorded();
    }

    @Test
    void renewedHandbrakeAfterAcceptedRecordsHold() {
        withSiblings(deleteFlowTask("task-2", Task.TaskStatus.REQUESTED));

        statusChange(Task.TaskStatus.ACCEPTED, Task.TaskStatus.ONHOLD);

        assertEquals("hold", capturedEvent().getType().getCode());
    }

    @Test
    void unchangedStatusRecordsNothing() {
        withSiblings(deleteFlowTask("task-2", Task.TaskStatus.REQUESTED));

        statusChange(Task.TaskStatus.ONHOLD, Task.TaskStatus.ONHOLD);

        assertNoEventRecorded();
    }

    @Test
    void serverDrivenTransitionRecordsNothing() {
        withSiblings(deleteFlowTask("task-2", Task.TaskStatus.REQUESTED));
        lastUpdatedTask = deleteFlowTask(UPDATED_TASK_ID, Task.TaskStatus.REQUESTED);

        interceptor.resourceUpdated(deleteFlowTask(UPDATED_TASK_ID, Task.TaskStatus.ONHOLD), lastUpdatedTask,
                new SystemRequestDetails());

        assertNoEventRecorded();
    }

    @Test
    void missingRequestDetailsRecordsNothing() {
        withSiblings(deleteFlowTask("task-2", Task.TaskStatus.REQUESTED));
        lastUpdatedTask = deleteFlowTask(UPDATED_TASK_ID, Task.TaskStatus.ONHOLD);

        interceptor.resourceUpdated(deleteFlowTask(UPDATED_TASK_ID, Task.TaskStatus.REQUESTED), lastUpdatedTask, null);

        assertNoEventRecorded();
    }

    @Test
    void aTaskThatWasNotPartOfTheFlowRecordsNothing() {
        Task previous = new Task();
        previous.setId("Task/" + UPDATED_TASK_ID);
        previous.setStatus(Task.TaskStatus.REQUESTED);
        lastUpdatedTask = deleteFlowTask(UPDATED_TASK_ID, Task.TaskStatus.ONHOLD);

        interceptor.resourceUpdated(previous, lastUpdatedTask, requestDetails);

        assertNoEventRecorded();
    }

    @Test
    void cancelledAndCompletedTasksDoNotWeighInOnTheAggregate() {
        withSiblings(deleteFlowTask("task-2", Task.TaskStatus.CANCELLED),
                deleteFlowTask("task-3", Task.TaskStatus.COMPLETED),
                deleteFlowTask("task-4", Task.TaskStatus.REQUESTED));

        statusChange(Task.TaskStatus.REQUESTED, Task.TaskStatus.ONHOLD);

        assertEquals("hold", capturedEvent().getType().getCode());
    }

    @Test
    void aTaskNotReferencingAPatientRecordsNothing() {
        Task previous = deleteFlowTask(UPDATED_TASK_ID, Task.TaskStatus.REQUESTED);
        previous.getFor().setReference("Group/g1");
        lastUpdatedTask = deleteFlowTask(UPDATED_TASK_ID, Task.TaskStatus.ONHOLD);

        interceptor.resourceUpdated(previous, lastUpdatedTask, requestDetails);

        assertNoEventRecorded();
    }

    @Test
    void thePatientIsLockedBeforeTheAggregateIsRead() {
        withSiblings(deleteFlowTask("task-2", Task.TaskStatus.REQUESTED));

        statusChange(Task.TaskStatus.REQUESTED, Task.TaskStatus.ONHOLD);

        InOrder inOrder = inOrder(aggregateLock, taskDao);
        inOrder.verify(aggregateLock).lockPatient(PATIENT_ID);
        inOrder.verify(taskDao).search(any(SearchParameterMap.class), any());
    }

    @Test
    void aFailureToRecordDoesNotFailTheStatusWrite() {
        withSiblings(deleteFlowTask("task-2", Task.TaskStatus.REQUESTED));
        when(participatingApps.serverDeviceId()).thenThrow(new IllegalStateException("no server Device"));

        statusChange(Task.TaskStatus.REQUESTED, Task.TaskStatus.ONHOLD);

        assertNoEventRecorded();
    }

    @Test
    void theEventIsWrittenInItsOwnTransaction() {
        // After a commit the original transaction is still bound but finished, so a
        // participating write would never be committed.
        withSiblings(deleteFlowTask("task-2", Task.TaskStatus.REQUESTED));

        statusChange(Task.TaskStatus.REQUESTED, Task.TaskStatus.ONHOLD);

        ArgumentCaptor<TransactionDefinition> captor = ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(transactionManager).getTransaction(captor.capture());
        assertEquals(TransactionDefinition.PROPAGATION_REQUIRES_NEW, captor.getValue().getPropagationBehavior());
    }

    @Test
    void eventIsAggregatedOnThePatientAndCarriesTheDeleteFlowMarker() {
        withSiblings(deleteFlowTask("task-2", Task.TaskStatus.REQUESTED));

        statusChange(Task.TaskStatus.REQUESTED, Task.TaskStatus.ONHOLD);

        AuditEvent event = capturedEvent();
        Coding security = event.getMeta().getSecurity().get(0);
        assertEquals("http://vzvz.nl/fhir/CodeSystem/koppeltaal-security-label", security.getSystem());
        assertEquals("kt2-delete-flow", security.getCode());
        assertEquals(AuditEvent.AuditEventOutcome._0, event.getOutcome());
        assertEquals("Patient/" + PATIENT_ID, event.getEntityFirstRep().getWhat().getReference());
        assertEquals("Device/server-dev", event.getAgentFirstRep().getWho().getReference());
        assertEquals("Device/server-dev", event.getSource().getObserver().getReference());
        assertEquals("Koppeltaal domein VZVZ", event.getSource().getSite());
        assertTrue(event.getMeta().getProfile().stream()
                .anyMatch(profile -> "http://koppeltaal.nl/fhir/StructureDefinition/KT2AuditEvent"
                        .equals(profile.getValue())));
    }
}
