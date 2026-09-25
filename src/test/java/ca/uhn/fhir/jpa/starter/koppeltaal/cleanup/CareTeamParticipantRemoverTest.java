package ca.uhn.fhir.jpa.starter.koppeltaal.cleanup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import org.hl7.fhir.r4.model.CareTeam;
import org.hl7.fhir.r4.model.Reference;
import org.junit.jupiter.api.Test;

class CareTeamParticipantRemoverTest {
	@Test
	void removesWholeMatchingEntriesAndPreservesTheOriginalAndOtherParticipants() {
		CareTeam original = new CareTeam().setSubject(new Reference("Patient/y"));
		original.setId("CareTeam/team/_history/3");
		original.addParticipant().setMember(new Reference("Patient/x")).addRole().setText("patient role");
		original.addParticipant().setMember(new Reference("Patient/x/_history/2"));
		original.addParticipant().setMember(new Reference("RelatedPerson/r"));
		original.addParticipant().setMember(new Reference("Practitioner/p"));
		CareTeam updated = CareTeamParticipantRemover.remove(original, Set.of("Patient/x", "RelatedPerson/r"));
		assertThat(updated.getParticipant()).singleElement()
				.satisfies(member -> assertThat(member.getMember().getReference()).isEqualTo("Practitioner/p"));
		assertThat(updated.getSubject().getReference()).isEqualTo("Patient/y");
		assertThat(updated.getIdElement().getVersionIdPart()).isEqualTo("3");
		assertThat(original.getParticipant()).hasSize(4);
	}

	@Test
	void anIndexMismatchStopsInsteadOfPretendingTheReferenceWasRemoved() {
		CareTeam team = new CareTeam();
		team.addParticipant().setMember(new Reference("Patient/y"));
		assertThatThrownBy(() -> CareTeamParticipantRemover.remove(team, Set.of("Patient/x")))
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("Patient/x");
	}

	@Test
	void externalReferenceWithTheSameIdDoesNotGetDetached() {
		CareTeam team = new CareTeam();
		team.addParticipant().setMember(new Reference("https://external.example/fhir/Patient/x"));
		team.addParticipant().setMember(new Reference("Patient/x"));
		assertThat(CareTeamParticipantRemover.remove(team, Set.of("Patient/x")).getParticipant()).singleElement()
				.satisfies(member -> assertThat(member.getMember().getReference()).startsWith("https://external.example/"));
	}
}
