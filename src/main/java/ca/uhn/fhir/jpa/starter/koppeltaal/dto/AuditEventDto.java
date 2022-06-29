package ca.uhn.fhir.jpa.starter.koppeltaal.dto;

import org.hl7.fhir.r4.model.Device;
import org.hl7.fhir.r4.model.Resource;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 *
 */
public class AuditEventDto {
	EventType eventType;
	Date dateTime = new Date();
	String outcome = "";
	/**
	 * This ID should always remain the same over all systems involved in the event. An event
	 * could be an update of a resource.
	 * Only when initiating a NEW event this value should get a new value. The initiator of
	 * the event should set the trace ID.
	 * Rules of the thumb:
	 * 1) this ID should remain the same if provided, unless something unrelated is started.
	 * 2) if no trace ID is provided, try to use any consistent identification, like a JTI.
	 * 3) the traceId should be propagated unchanged to all calls to subsystems and all related
	 * events.
	 *
	 */
	String traceId;
	/**
	 * The span ID should remain the same over the processing within the same system and should be
	 * set on ENTRY.
	 * Rules of the thumb:
	 * 1) On entry, a new span ID is generated.
	 * 2) If an incoming call contains a span id, this id should be moved to the parentSpanId.
	 * 3) The value of the span ID and parentSpanId should be propagated to all calls to subsystems.
	 */
	String spanId;
	/**
	 * The parentSpanId should be filled with the spanId of the incoming call. If the incoming
	 * call does not have a span ID, it should NOT be filled.
	 */
	String parentSpanId;
	Device device;
	String query;
	List<Resource> resources = new ArrayList<>();

	public void addResource(Resource r) {
		this.resources.add(r);
	}

	public Date getDateTime() {
		return dateTime;
	}

	public void setDateTime(Date dateTime) {
		this.dateTime = dateTime;
	}

	public Device getDevice() {
		return device;
	}

	public void setDevice(Device device) {
		this.device = device;
	}

	public EventType getEventType() {
		return eventType;
	}

	public void setEventType(EventType eventType) {
		this.eventType = eventType;
	}

	public String getOutcome() {
		return outcome;
	}

	public void setOutcome(String outcome) {
		this.outcome = outcome;
	}

	public String getParentSpanId() {
		return parentSpanId;
	}

	public void setParentSpanId(String parentSpanId) {
		this.parentSpanId = parentSpanId;
	}

	public String getQuery() {
		return query;
	}

	public void setQuery(String query) {
		this.query = query;
	}

	public List<Resource> getResources() {
		return new ArrayList<>(resources);
	}

	public String getSpanId() {
		return spanId;
	}

	public void setSpanId(String spanId) {
		this.spanId = spanId;
	}

	public String getTraceId() {
		return traceId;
	}

	public void setTraceId(String traceId) {
		this.traceId = traceId;
	}


	public enum EventType {
		Read,
		Update,
		Delete,
		Create,
		Search,
		Capability,
		Launch,
		Launched,
		SendNotification,
		ReceiveNotification,
		StatusChange
	}

}
