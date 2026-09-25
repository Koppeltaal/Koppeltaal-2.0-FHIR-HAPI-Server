package ca.uhn.fhir.jpa.starter.koppeltaal.cleanup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Task;
import org.junit.jupiter.api.Test;

class TaskOwnerTaggingTest {
	private Task original() {
		Task task = new Task().setFor(new Reference("Patient/b")).setOwner(new Reference("Patient/a/_history/2"))
				.setStatus(Task.TaskStatus.INPROGRESS).setIntent(Task.TaskIntent.ORDER);
		task.setId("Task/retained/_history/4");
		return task;
	}

	@Test
	void preparesOnlyTheTagAndPreservesTheOriginal() {
		Task original = original();
		original.getMeta().setVersionId("4").setLastUpdated(java.util.Date.from(java.time.Instant.parse("2024-09-25T10:00:00Z")));
		original.getMeta().addTag().setSystem("urn:test").setCode("existing");
		Task before = original.copy();
		var addition = TaskOwnerTagging.prepare(original, "Patient/a");
		assertThat(addition.taskId().getValue()).isEqualTo("Task/retained");
		assertThat(addition.meta().getTag()).singleElement().satisfies(tag -> {
			assertThat(tag.getSystem()).isEqualTo("http://hl7.org/fhir/issue-type");
			assertThat(tag.getCode()).isEqualTo("deleted");
			assertThat(tag.getDisplay()).isEqualTo("Deleted");
		});
		assertThat(addition.meta().hasVersionId()).isFalse();
		assertThat(addition.meta().hasLastUpdated()).isFalse();
		assertThat(addition.meta().hasSecurity()).isFalse();
		assertThat(original.equalsDeep(before)).isTrue();
	}

	@Test
	void refusesAStalePlanAnUnestablishedBeneficiaryAndAnExternalNamesake() {
		assertThatThrownBy(() -> TaskOwnerTagging.prepare(original(), "Patient/c")).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> TaskOwnerTagging.prepare(original().setFor(null), "Patient/a"))
				.isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> TaskOwnerTagging.prepare(original().setFor(new Reference("Patient/a")), "Patient/a"))
				.isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> TaskOwnerTagging.prepare(original().setOwner(new Reference("https://external.example/Patient/a")), "Patient/a"))
				.isInstanceOf(IllegalStateException.class);
	}
}
