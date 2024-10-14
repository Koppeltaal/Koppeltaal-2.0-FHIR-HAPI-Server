package ca.uhn.fhir.jpa.starter.koppeltaal.interceptor;

import ca.uhn.fhir.rest.api.RestOperationTypeEnum;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.exceptions.UnprocessableEntityException;

import org.hl7.fhir.r4.model.Task;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.Assert.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ValidSubTaskInterceptorTest {

    private ValidSubTaskInterceptor interceptor;

    @Mock
    private RequestDetails requestDetails;

    @BeforeEach
    void setUp() {
        interceptor = new ValidSubTaskInterceptor();
    }

    @Test
    void testIncomingRequestPreHandled_TaskWithPartOf() {
        Task task = new Task();
        task.addPartOf().setReference("Task/123");

        when(requestDetails.getResource()).thenReturn(task);
        when(requestDetails.getRestOperationType()).thenReturn(RestOperationTypeEnum.CREATE);

        assertDoesNotThrow(() -> interceptor.incomingRequestPreHandled(requestDetails));
    }

    @Test
    void testIncomingRequestPreHandled_TaskWithoutPartOfAndInvalidCode() {
        Task task = new Task();
        task.getCode().addCoding().setSystem("http://vzvz.nl/fhir/CodeSystem/koppeltaal-task-code").setCode("view");

        when(requestDetails.getResource()).thenReturn(task);
        when(requestDetails.getRestOperationType()).thenReturn(RestOperationTypeEnum.CREATE);

        assertThrows(UnprocessableEntityException.class, () -> interceptor.incomingRequestPreHandled(requestDetails));
    }

    @Test
    void testIncomingRequestPreHandled_NotATask() {
        when(requestDetails.getResource()).thenReturn(null);
        when(requestDetails.getRestOperationType()).thenReturn(RestOperationTypeEnum.CREATE);

        assertDoesNotThrow(() -> interceptor.incomingRequestPreHandled(requestDetails));
    }

    @Test
    void testIncomingRequestPreHandled_TaskWithDifferentCode() {
        Task task = new Task();
        task.getCode().addCoding().setSystem("http://vzvz.nl/fhir/CodeSystem/koppeltaal-task-code")
                .setCode("differentCode");

        when(requestDetails.getResource()).thenReturn(task);
        when(requestDetails.getRestOperationType()).thenReturn(RestOperationTypeEnum.CREATE);

        assertDoesNotThrow(() -> interceptor.incomingRequestPreHandled(requestDetails));
    }

    @Test
    void testIncomingRequestPreHandled_TaskWithPartOfAndInvalidCode() {
        Task task = new Task();
        task.addPartOf().setReference("Task/123");
        task.getCode().addCoding().setSystem("http://vzvz.nl/fhir/CodeSystem/koppeltaal-task-code").setCode("view");

        when(requestDetails.getResource()).thenReturn(task);
        when(requestDetails.getRestOperationType()).thenReturn(RestOperationTypeEnum.CREATE);

        assertDoesNotThrow(() -> interceptor.incomingRequestPreHandled(requestDetails));
    }

    @Test
    void testIncomingRequestPreHandled_TaskWithPartOf_UpdateOperation() {
        Task task = new Task();
        task.addPartOf().setReference("Task/123");

        when(requestDetails.getResource()).thenReturn(task);
        when(requestDetails.getRestOperationType()).thenReturn(RestOperationTypeEnum.UPDATE);

        assertDoesNotThrow(() -> interceptor.incomingRequestPreHandled(requestDetails));
    }

    @Test
    void testIncomingRequestPreHandled_TaskWithoutPartOfAndInvalidCode_UpdateOperation() {
        Task task = new Task();
        task.getCode().addCoding().setSystem("http://vzvz.nl/fhir/CodeSystem/koppeltaal-task-code").setCode("view");

        when(requestDetails.getResource()).thenReturn(task);
        when(requestDetails.getRestOperationType()).thenReturn(RestOperationTypeEnum.UPDATE);

        assertThrows(UnprocessableEntityException.class, () -> interceptor.incomingRequestPreHandled(requestDetails));
    }

    @Test
    void testIncomingRequestPreHandled_OtherOperationTypes() {
        Task task = new Task();
        task.getCode().addCoding().setSystem("http://vzvz.nl/fhir/CodeSystem/koppeltaal-task-code").setCode("view");

        when(requestDetails.getResource()).thenReturn(task);

        for (RestOperationTypeEnum operationType : RestOperationTypeEnum.values()) {
            if (operationType != RestOperationTypeEnum.CREATE && operationType != RestOperationTypeEnum.UPDATE) {
                when(requestDetails.getRestOperationType()).thenReturn(operationType);
                assertDoesNotThrow(() -> interceptor.incomingRequestPreHandled(requestDetails));
            }
        }
    }

    @Test
    void testIncomingRequestPreHandled_TaskWithoutPartOfAndInvalidCode_ErrorMessage() {
        Task task = new Task();
        task.getCode().addCoding().setSystem("http://vzvz.nl/fhir/CodeSystem/koppeltaal-task-code").setCode("view");

        when(requestDetails.getResource()).thenReturn(task);
        when(requestDetails.getRestOperationType()).thenReturn(RestOperationTypeEnum.CREATE);

        UnprocessableEntityException exception = assertThrows(UnprocessableEntityException.class,
                () -> interceptor.incomingRequestPreHandled(requestDetails));
        assertEquals("Task with code 'view' should have a 'partOf' reference", exception.getMessage());
    }

    @Test
    void testIncomingRequestPreHandled_TaskWithPartOfAndDifferentCode() {
        Task task = new Task();
        task.addPartOf().setReference("Task/123");
        task.getCode().addCoding().setSystem("http://vzvz.nl/fhir/CodeSystem/koppeltaal-task-code")
                .setCode("differentCode");

        when(requestDetails.getResource()).thenReturn(task);
        when(requestDetails.getRestOperationType()).thenReturn(RestOperationTypeEnum.CREATE);

        assertDoesNotThrow(() -> interceptor.incomingRequestPreHandled(requestDetails));
    }

    @Test
    void testIncomingRequestPreHandled_TaskWithoutPartOfAndDifferentSystem() {
        Task task = new Task();
        task.getCode().addCoding().setSystem("http://different-system.com").setCode("view");

        when(requestDetails.getResource()).thenReturn(task);
        when(requestDetails.getRestOperationType()).thenReturn(RestOperationTypeEnum.CREATE);

        assertDoesNotThrow(() -> interceptor.incomingRequestPreHandled(requestDetails));
    }

    @Test
    void testIncomingRequestPreHandled_TaskWithoutPartOfAndNoCode() {
        Task task = new Task();

        when(requestDetails.getResource()).thenReturn(task);
        when(requestDetails.getRestOperationType()).thenReturn(RestOperationTypeEnum.CREATE);

        assertDoesNotThrow(() -> interceptor.incomingRequestPreHandled(requestDetails));
    }
}