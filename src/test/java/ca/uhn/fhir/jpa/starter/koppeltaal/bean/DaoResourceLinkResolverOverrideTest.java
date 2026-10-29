package ca.uhn.fhir.jpa.starter.koppeltaal.bean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ca.uhn.fhir.i18n.Msg;
import ca.uhn.fhir.interceptor.model.RequestPartitionId;
import ca.uhn.fhir.jpa.model.cross.IResourceLookup;
import ca.uhn.fhir.jpa.searchparam.extractor.PathAndRef;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.api.server.storage.TransactionDetails;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;

import org.hl7.fhir.r4.model.Reference;
import org.junit.jupiter.api.Test;

/**
 * An erased patient has to stay nameable in the AuditEvent that records its
 * destruction, along every path the reference is indexed on.
 */
class DaoResourceLinkResolverOverrideTest {

	/** The generic entity index and the type-specific one HAPI derives from it. */
	private static final String GENERIC_PATH = "AuditEvent.entity.what";
	private static final String PATIENT_PATH = "AuditEvent.entity.what.where(resolve() is Patient)";

	private InvalidRequestException thrownByHapi;

	private final DaoResourceLinkResolverOverride<?> resolver = new DaoResourceLinkResolverOverride<>() {
		@Override
		IResourceLookup findTargetResourceInHapi(RequestPartitionId partitionId, String sourceResourceName,
				PathAndRef pathAndRef, RequestDetails request, TransactionDetails transactionDetails) {

			throw thrownByHapi;
		}
	};

	@Test
	void anErasedTargetIsRecordedOnEveryIndexedPath() {

		hapiReports(1094, "Resource Patient/gone not found, specified in path: ");

		assertThat(resolve(GENERIC_PATH)).isNull();
		assertThat(resolve(PATIENT_PATH)).isNull();
	}

	@Test
	void aDeletedTargetIsRecordedToo() {

		hapiReports(1096, "Resource Patient/gone is deleted, specified in path: ");

		assertThat(resolve(PATIENT_PATH)).isNull();
	}

	/** Only "the target is not there" is waved through; other complaints stand. */
	@Test
	void anyOtherComplaintStillStops() {

		hapiReports(1099, "Non-unique ID specified, can not process request");

		assertThatThrownBy(() -> resolve(PATIENT_PATH))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("Non-unique ID");
	}

	/** Outside the entity paths nothing is tolerated at all. */
	@Test
	void anOrdinaryReferenceIsUnaffected() {

		hapiReports(1094, "Resource Patient/gone not found, specified in path: ");

		assertThatThrownBy(() -> resolve("Task.for"))
				.isInstanceOf(InvalidRequestException.class);
	}

	private void hapiReports(int code, String message) {
		thrownByHapi = new InvalidRequestException(Msg.code(code) + message);
	}

	private IResourceLookup resolve(String path) {
		final PathAndRef pathAndRef = new PathAndRef("entity", path, new Reference("Patient/gone"), false);
		return resolver.findTargetResource(RequestPartitionId.allPartitions(), "AuditEvent", pathAndRef, null, null);
	}
}
