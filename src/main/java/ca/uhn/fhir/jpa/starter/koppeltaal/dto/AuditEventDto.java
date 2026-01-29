package ca.uhn.fhir.jpa.starter.koppeltaal.dto;

import org.apache.commons.lang3.builder.ToStringBuilder;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.hl7.fhir.r4.model.Reference;

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
	String outcomeDesc = ""; //This field is only used when the operationOutcome is not provided, like in a failed Subscription delivery
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
  List<AgentAndTypeDto> agents = new ArrayList<>();
	String query;
	List<Reference> resources = new ArrayList<>();
  private String site;

  public void addResource(Reference r) {
		this.resources.add(r);
	}

	public Date getDateTime() {
		return dateTime;
	}

	public void setDateTime(Date dateTime) {
		this.dateTime = dateTime;
	}

	public List<AgentAndTypeDto> getAgents() {
		return agents;
	}

	public void addAgent(Reference agent, Coding type, boolean requestor) {
		this.agents.add(new AgentAndTypeDto(agent, type, requestor));
	}
	public void addAgent(Reference agent, Coding type) {
		this.agents.add(new AgentAndTypeDto(agent, type));
	}
	public void addAgent(Reference agent, Coding type, boolean requestor, String networkAddress) {
		this.agents.add(new AgentAndTypeDto(agent, type, requestor, networkAddress));
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

	public String getOutcomeDesc() {
		return outcomeDesc;
	}
	
	public void setOutcomeDesc(String outcomeDesc) {
		this.outcomeDesc = outcomeDesc;
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

	public List<Reference> getResources() {
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
      ", agents=" + agents +
      ", query='" + query + '\'' +
      ", resources=" + resources +
      '}';
  }

  public void setSite(String site) {
    this.site = site;
  }

  public String getSite() {
    return site;
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

  public static class AgentAndTypeDto {
    final Reference agent;
    final Coding type;
    final boolean requester;
    final String networkAddress;

    public AgentAndTypeDto(Reference agent, Coding type) {
      this.agent = agent;
      this.type = type;
      this.requester = true;
      this.networkAddress = null;
    }

    public AgentAndTypeDto(Reference agent, Coding type, boolean requester) {
      this.agent = agent;
      this.type = type;
      this.requester = requester;
      this.networkAddress = null;
    }

    public AgentAndTypeDto(Reference agent, Coding type, boolean requester, String networkAddress) {
      this.agent = agent;
      this.type = type;
      this.requester = requester;
      this.networkAddress = networkAddress;
    }

    public boolean isRequester() {
      return requester;
    }

    public Reference getAgent() {
      return agent;
    }

    public Coding getType() {
      return type;
    }

    public String getNetworkAddress() {
      return networkAddress;
    }

    @Override
    public String toString() {
      return new ToStringBuilder(this)
        .append("agent", agent)
        .append("type", type)
        .append("requester", requester)
        .append("networkAddress", networkAddress)
        .toString();
    }
  }
}
