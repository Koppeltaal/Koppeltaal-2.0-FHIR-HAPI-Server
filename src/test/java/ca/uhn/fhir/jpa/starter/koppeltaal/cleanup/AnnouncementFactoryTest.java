package ca.uhn.fhir.jpa.starter.koppeltaal.cleanup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.uhn.fhir.jpa.starter.koppeltaal.util.TraceHeaders;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.hl7.fhir.r4.model.AuditEvent;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.DomainResource;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Task;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AnnouncementFactoryTest {

	private static final Instant NOW = Instant.parse("2026-08-21T12:00:00Z");
	private static final TraceHeaders NO_TRACE = new TraceHeaders(null, null, null);

	private AnnouncementFactory factory;

	@BeforeEach
	void setUp() {
		factory = new AnnouncementFactory(new CleanupProperties(), Clock.fixed(NOW, ZoneOffset.UTC));
	}

	@Test
	void deletePendingTaskMatchesProfileContract() {
		Task task = factory.buildDeletePendingTask(new IdType("Patient", "p1"),
				new IdType("Device", "app-1"), new IdType("Device", "server-dev"));

		assertEquals("http://koppeltaal.nl/fhir/StructureDefinition/KT2DeletePendingTask",
				task.getMeta().getProfile().get(0).getValue());
		Coding security = task.getMeta().getSecurity().get(0);
		assertEquals("http://vzvz.nl/fhir/CodeSystem/koppeltaal-security-label", security.getSystem());
		assertEquals("kt2-delete-flow", security.getCode());
		assertEquals(Task.TaskStatus.REQUESTED, task.getStatus());
		assertEquals(Task.TaskIntent.ORDER, task.getIntent());
		Coding code = task.getCode().getCodingFirstRep();
		assertEquals("http://vzvz.nl/fhir/CodeSystem/koppeltaal-task-code", code.getSystem());
		assertEquals("delete-pending", code.getCode());
		assertEquals("Patient/p1", task.getFor().getReference());
		assertEquals("Device/app-1", task.getOwner().getReference());
		assertEquals("Device/server-dev", task.getRequester().getReference());
		assertEquals(Instant.parse("2026-08-21T12:00:00Z").toEpochMilli(), task.getAuthoredOn().getTime());
		assertEquals(Instant.parse("2026-09-20T12:00:00Z").toEpochMilli(),
				task.getRestriction().getPeriod().getEnd().getTime());
	}

	@Test
	void archiveEventMatchesExampleContract() {
		AuditEvent event = factory.buildArchiveEvent(new IdType("Patient", "p1"),
				new IdType("Device", "server-dev"), "Koppeltaal domein VZVZ", NO_TRACE);

		assertEquals("http://koppeltaal.nl/fhir/StructureDefinition/KT2AuditEvent",
				event.getMeta().getProfile().get(0).getValue());
		assertEquals("kt2-delete-flow", event.getMeta().getSecurity().get(0).getCode());
		assertEquals("http://terminology.hl7.org/CodeSystem/iso-21089-lifecycle", event.getType().getSystem());
		assertEquals("archive", event.getType().getCode());
		assertEquals(AuditEvent.AuditEventAction.C, event.getAction());
		assertEquals("0", event.getOutcomeElement().getValueAsString());
		AuditEvent.AuditEventAgentComponent agent = event.getAgentFirstRep();
		assertEquals("Device/server-dev", agent.getWho().getReference());
		assertTrue(agent.getRequestor());
		assertEquals("110153", agent.getType().getCodingFirstRep().getCode());
		assertEquals("Koppeltaal domein VZVZ", event.getSource().getSite());
		assertEquals("Device/server-dev", event.getSource().getObserver().getReference());
		assertEquals("Patient/p1", event.getEntityFirstRep().getWhat().getReference());
		assertEquals("Patient", event.getEntityFirstRep().getType().getCode());
		assertEquals("1", event.getEntityFirstRep().getRole().getCode());
	}

	@Test
	void deletePendingTaskNamesTheServerDeviceAsResourceOrigin() {
		Task task = factory.buildDeletePendingTask(new IdType("Patient", "p1"),
				new IdType("Device", "app-1"), new IdType("Device", "server-dev"));

		Reference origin = resourceOrigin(task);
		assertEquals("Device/server-dev", origin.getReference());
		assertEquals("Device", origin.getType());
	}

	@Test
	void lifecycleEventNamesTheServerDeviceAsResourceOrigin() {
		AuditEvent event = factory.buildLifecycleEvent(DeleteFlowLifecycleEvent.BLOCKED, new IdType("Patient", "p1"),
				new IdType("Device", "server-dev"), "Koppeltaal domein VZVZ", NO_TRACE);

		Reference origin = resourceOrigin(event);
		assertEquals("Device/server-dev", origin.getReference());
		assertEquals("Device", origin.getType());
	}

	@Test
	void lifecycleEventCarriesTheTraceHeadersOfWhatCausedIt() {
		AuditEvent event = factory.buildLifecycleEvent(DeleteFlowLifecycleEvent.BLOCKED, new IdType("Patient", "p1"),
				new IdType("Device", "server-dev"), "Koppeltaal domein VZVZ",
				new TraceHeaders("trace-1", "request-1", "client-request-1"));

		assertEquals("trace-1", idExtension(event, "http://koppeltaal.nl/fhir/StructureDefinition/trace-id"));
		assertEquals("request-1", idExtension(event, "http://koppeltaal.nl/fhir/StructureDefinition/request-id"));
		assertEquals("client-request-1",
				idExtension(event, "http://koppeltaal.nl/fhir/StructureDefinition/correlation-id"));
	}

	/** A run the server starts itself has a trace, but no request and nothing to correlate with. */
	@Test
	void anAbsentTraceHeaderIsLeftOut() {
		AuditEvent event = factory.buildLifecycleEvent(DeleteFlowLifecycleEvent.DESTROYED, new IdType("Patient", "p1"),
				new IdType("Device", "server-dev"), "Koppeltaal domein VZVZ", new TraceHeaders("trace-1", null, ""));

		assertEquals("trace-1", idExtension(event, "http://koppeltaal.nl/fhir/StructureDefinition/trace-id"));
		assertNull(event.getExtensionByUrl("http://koppeltaal.nl/fhir/StructureDefinition/request-id"));
		assertNull(event.getExtensionByUrl("http://koppeltaal.nl/fhir/StructureDefinition/correlation-id"));
	}

	/** KT2AuditEvent's trace header extensions take an id, nothing else. */
	private static String idExtension(AuditEvent event, String url) {
		Extension extension = event.getExtensionByUrl(url);
		assertNotNull(extension, url);
		return assertInstanceOf(IdType.class, extension.getValue()).getValue();
	}

	private static Reference resourceOrigin(DomainResource resource) {
		Extension extension = resource.getExtensionByUrl("http://koppeltaal.nl/fhir/StructureDefinition/resource-origin");
		assertNotNull(extension, "resource-origin extension");
		return (Reference) extension.getValue();
	}
}
