package ca.uhn.fhir.jpa.starter.koppeltaal.interceptor;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ca.uhn.fhir.rest.api.RestOperationTypeEnum;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import ca.uhn.fhir.rest.server.exceptions.ForbiddenOperationException;
import ca.uhn.fhir.rest.server.exceptions.UnprocessableEntityException;
import ca.uhn.fhir.rest.server.servlet.ServletRequestDetails;
import org.hl7.fhir.r4.model.AuditEvent;
import org.hl7.fhir.r4.model.CareTeam;
import org.hl7.fhir.r4.model.DomainResource;
import org.hl7.fhir.r4.model.Meta;
import org.hl7.fhir.r4.model.Parameters;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Task;
import org.junit.jupiter.api.Test;

class DeleteFlowMarkerInterceptorTest {

	private final DeleteFlowMarkerInterceptor interceptor = new DeleteFlowMarkerInterceptor();
	private final RequestDetails client = new ServletRequestDetails();
	private final RequestDetails server = new SystemRequestDetails();

	@Test
	void aClientCannotCreateAnyResourceCarryingTheMarker() {
		for (DomainResource resource : new DomainResource[] {new Patient(), new AuditEvent(), new CareTeam()}) {
			assertThatThrownBy(() -> interceptor.resourceCreating(labelled(resource), client))
					.isInstanceOf(UnprocessableEntityException.class);
		}
	}

	@Test
	void theServerCreatesLabelledResources() {
		assertThatCode(() -> interceptor.resourceCreating(labelled(new AuditEvent()), server)).doesNotThrowAnyException();
	}

	@Test
	void aClientCannotAddTheMarkerOnUpdate() {
		assertThatThrownBy(() -> interceptor.resourceUpdating(new CareTeam(), labelled(new CareTeam()), client))
				.isInstanceOf(ForbiddenOperationException.class);
	}

	@Test
	void aClientCannotDropTheMarkerOnUpdate() {
		assertThatThrownBy(() -> interceptor.resourceUpdating(labelled(new Task()), new Task(), client))
				.isInstanceOf(ForbiddenOperationException.class);
	}

	@Test
	void anUpdateThatKeepsTheMarkerIsLeftToTheTaskRules() {
		assertThatCode(() -> interceptor.resourceUpdating(labelled(new Task()), labelled(new Task()), client))
				.doesNotThrowAnyException();
	}

	@Test
	void aClientCannotDeleteALabelledResource() {
		assertThatThrownBy(() -> interceptor.resourceDeleting(labelled(new Task()), client))
				.isInstanceOf(ForbiddenOperationException.class);
		assertThatCode(() -> interceptor.resourceDeleting(new Task(), client)).doesNotThrowAnyException();
	}

	@Test
	void aClientCannotAddOrRemoveTheMarkerThroughTheMetaOperations() {
		for (RestOperationTypeEnum operation : new RestOperationTypeEnum[] {RestOperationTypeEnum.META_ADD,
				RestOperationTypeEnum.META_DELETE}) {
			client.setResource(metaParameters(labelled(new Task()).getMeta()));
			assertThatThrownBy(() -> interceptor.incomingRequestPreHandled(client, operation))
					.isInstanceOf(ForbiddenOperationException.class);
		}
	}

	@Test
	void otherTagsGoThroughTheMetaOperations() {
		client.setResource(metaParameters(new Meta().addTag("http://example.org/tags", "other", null)));
		assertThatCode(() -> interceptor.incomingRequestPreHandled(client, RestOperationTypeEnum.META_ADD))
				.doesNotThrowAnyException();
	}

	private static <T extends DomainResource> T labelled(T resource) {
		resource.getMeta().addSecurity("http://vzvz.nl/fhir/CodeSystem/koppeltaal-security-label", "kt2-delete-flow", null);
		return resource;
	}

	private static Parameters metaParameters(Meta meta) {
		Parameters parameters = new Parameters();
		parameters.addParameter().setName("meta").setValue(meta);
		return parameters;
	}
}
