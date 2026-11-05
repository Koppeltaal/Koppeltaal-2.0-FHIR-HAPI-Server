package ca.uhn.fhir.jpa.starter.koppeltaal.bean;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ca.uhn.fhir.jpa.api.config.JpaStorageSettings;
import ca.uhn.fhir.jpa.api.svc.IIdHelperService;
import ca.uhn.fhir.jpa.dao.data.IResourceLinkDao;
import ca.uhn.fhir.jpa.dao.expunge.ResourceTableFKProvider;
import ca.uhn.fhir.jpa.model.config.PartitionSettings;
import ca.uhn.fhir.jpa.model.dao.JpaPid;
import ca.uhn.fhir.jpa.model.entity.ResourceLink;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;

import java.util.List;
import java.util.Set;

import org.hl7.fhir.r4.model.IdType;
import org.junit.jupiter.api.Test;

/**
 * The erase removes a patient that retained AuditEvents still name. Everything
 * else that points at it has to keep stopping the erase.
 */
class DeleteExpungeSqlBuilderOverrideTest {

	private static final Set<JpaPid> PATIENT = Set.of(JpaPid.fromId(1929L));

	private final IResourceLinkDao resourceLinkDao = mock(IResourceLinkDao.class);
	private final IIdHelperService<JpaPid> idHelper = mock(IIdHelperService.class);

	private final DeleteExpungeSqlBuilderOverride builder = new DeleteExpungeSqlBuilderOverride(
			new ResourceTableFKProvider(), new JpaStorageSettings(), idHelper, resourceLinkDao,
			new PartitionSettings());

	@Test
	void retainedAuditEventsDoNotStopTheErase() {

		referredToBy(link("AuditEvent", "AuditEvent.entity.what"), link("AuditEvent", "AuditEvent.entity.what"));

		assertThatCode(() -> builder.validateOkToDeleteAndExpunge(PATIENT, false, null)).doesNotThrowAnyException();
	}

	@Test
	void nothingReferringMeansNothingToWaveThrough() {

		referredToBy();

		assertThatCode(() -> builder.validateOkToDeleteAndExpunge(PATIENT, false, null)).doesNotThrowAnyException();
	}

	/**
	 * A Task the deletion scope missed: erasing past it would orphan a live
	 * resource, so HAPI's own refusal stands.
	 */
	@Test
	void anythingElseStillStopsTheErase() {

		referredToBy(link("Task", "Task.for"));
		when(idHelper.resourceIdFromPidOrThrowException(any(), any()))
				.thenReturn(new IdType("Task", "task-1"), new IdType("Patient", "patient-1"));

		assertThatThrownBy(() -> builder.validateOkToDeleteAndExpunge(PATIENT, false, null))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("Task.for");
	}

	/** One stray referrer among the audit events is enough to stop it. */
	@Test
	void oneNonAuditReferrerAmongAuditEventsStopsTheErase() {

		referredToBy(link("AuditEvent", "AuditEvent.entity.what"), link("CareTeam", "CareTeam.subject"));
		when(idHelper.resourceIdFromPidOrThrowException(any(), any()))
				.thenReturn(new IdType("CareTeam", "careteam-1"), new IdType("Patient", "patient-1"));

		assertThatThrownBy(() -> builder.validateOkToDeleteAndExpunge(PATIENT, false, null))
				.isInstanceOf(InvalidRequestException.class);
	}

	private void referredToBy(ResourceLink... links) {
		when(resourceLinkDao.findWithTargetPidIn(anyList())).thenReturn(List.of(links));
	}

	private static ResourceLink link(String sourceType, String sourcePath) {
		final ResourceLink link = mock(ResourceLink.class);
		when(link.getSourceResourceType()).thenReturn(sourceType);
		when(link.getSourcePath()).thenReturn(sourcePath);
		when(link.getSourceResourcePk()).thenReturn(JpaPid.fromId(7L));
		when(link.getTargetResourcePid()).thenReturn(1929L);
		return link;
	}
}
