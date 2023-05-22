package ca.uhn.fhir.jpa.starter.koppeltaal.dto;

import org.hl7.fhir.instance.model.api.IBaseOperationOutcome;
import org.hl7.fhir.r4.model.Device;
import org.hl7.fhir.r4.model.OperationOutcome;
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
  OperationOutcome operationOutcome;
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
	 * The request ID should remain the same over the processing within the same system and should be
	 * set on ENTRY.
	 * Rules of the thumb:
	 * 1) On entry, a new span ID is generated.
	 * 2) If an incoming call contains a request id, this id should be moved to the correlationId.
	 * 3) The value of the request ID and correlationId should be propagated to all calls to subsystems.
	 */
	String requestId;
	/**
	 * The correlationId should be filled with the requestId of the incoming call if the server changes the requestId.
   * If the incoming call does not have a request ID, it should NOT be filled.
	 */
	String correlationId;
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

	public String getCorrelationId() {
		return correlationId;
	}

	public void setCorrelationId(String correlationId) {
		this.correlationId = correlationId;
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

	public String getRequestId() {
		return requestId;
	}

	public void setRequestId(String requestId) {
		this.requestId = requestId;
	}

	public String getTraceId() {
		return traceId;
	}

	public void setTraceId(String traceId) {
		this.traceId = traceId;
	}

  public OperationOutcome getOperationOutcome() {
    return operationOutcome;
  }

  public void setOperationOutcome(OperationOutcome operationOutcome) {
    this.operationOutcome = operationOutcome;
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

  @Override
  public String toString() {
    return "AuditEventDto{" +
      "eventType=" + eventType +
      ", dateTime=" + dateTime +
      ", outcome='" + outcome + '\'' +
      ", operationOutcome=" + operationOutcome +
      ", traceId='" + traceId + '\'' +
      ", requestId='" + requestId + '\'' +
      ", correlationId='" + correlationId + '\'' +
      ", device=" + device +
      ", query='" + query + '\'' +
      ", resources=" + resources +
      '}';
  }
}
