package ca.uhn.fhir.jpa.starter.koppeltaal.cleanup;

import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.Meta;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Task;

/** Marks a retained Task without changing its content or its last activity time. */
public final class TaskOwnerTagging {
	public static final String SYSTEM = "http://hl7.org/fhir/issue-type";
	public static final String CODE = "deleted";

	private TaskOwnerTagging() { }

	record TagAddition(IIdType taskId, Meta meta) { }

	static TagAddition prepare(Task original, String patientReference) {
		String owner = patientReference(original.getOwner());
		String beneficiary = patientReference(original.getFor());
		if (!patientReference.equals(owner) || beneficiary == null || beneficiary.equals(owner)) {
			throw new IllegalStateException("Task owner tagging plan does not match the resource: "
					+ original.getIdElement().toUnqualifiedVersionless().getValue());
		}
		return new TagAddition(original.getIdElement().toUnqualifiedVersionless(), deletedReferenceTag());
	}

	static Meta deletedReferenceTag() {
		Meta meta = new Meta();
		// Koppeltaal convention: reuse the reference-to-deleted-content issue code as a workflow tag.
		// https://hl7.org/fhir/R4/codesystem-issue-type.html#issue-type-deleted
		meta.addTag().setSystem(SYSTEM).setCode(CODE).setDisplay("Deleted");
		return meta;
	}

	private static String patientReference(Reference reference) {
		if (!reference.hasReference()) return null;
		IdType id = new IdType(reference.getReference());
		return !id.hasBaseUrl() && "Patient".equals(id.getResourceType()) && id.hasIdPart()
				? id.toUnqualifiedVersionless().getValue() : null;
	}
}
