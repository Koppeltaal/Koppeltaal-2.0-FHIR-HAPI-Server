package ca.uhn.fhir.jpa.starter.koppeltaal.cleanup;

import java.util.LinkedHashSet;
import java.util.Set;
import org.hl7.fhir.r4.model.CareTeam;
import org.hl7.fhir.r4.model.IdType;

final class CareTeamParticipantRemover {
	private CareTeamParticipantRemover() { }

	static CareTeam remove(CareTeam original, Set<String> references) {
		CareTeam updated = original.copy();
		Set<String> missing = new LinkedHashSet<>(references);
		updated.getParticipant().removeIf(participant -> {
			if (!participant.hasMember() || !participant.getMember().hasReference()) return false;
			IdType member = new IdType(participant.getMember().getReference());
			if (member.hasBaseUrl()) return false;
			String reference = member.toUnqualifiedVersionless().getValue();
			if (!references.contains(reference)) return false;
			missing.remove(reference);
			return true;
		});
		if (!missing.isEmpty()) {
			throw new IllegalStateException("CareTeam participant indexes do not match the resource: "
					+ original.getIdElement().toUnqualifiedVersionless().getValue() + " is missing " + missing);
		}
		return updated;
	}
}
