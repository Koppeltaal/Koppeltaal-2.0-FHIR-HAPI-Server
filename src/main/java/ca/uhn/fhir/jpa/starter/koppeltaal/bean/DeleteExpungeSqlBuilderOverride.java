package ca.uhn.fhir.jpa.starter.koppeltaal.bean;

import ca.uhn.fhir.jpa.api.config.JpaStorageSettings;
import ca.uhn.fhir.jpa.api.svc.IIdHelperService;
import ca.uhn.fhir.jpa.dao.data.IResourceLinkDao;
import ca.uhn.fhir.jpa.dao.expunge.ResourceTableFKProvider;
import ca.uhn.fhir.jpa.delete.batch2.DeleteExpungeSqlBuilder;
import ca.uhn.fhir.jpa.model.config.PartitionSettings;
import ca.uhn.fhir.jpa.model.dao.JpaPid;
import ca.uhn.fhir.jpa.model.entity.ResourceLink;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.hl7.fhir.r4.model.AuditEvent;

/**
 * <p>
 * Lets the GDPR erase remove a patient that retained AuditEvents still point at.
 * HAPI refuses a delete-expunge as long as anything outside the deletion set
 * refers to it, and the AuditEvents are deliberately outside it: OPG-013 keeps
 * them as the NEN 7513 record and they go on naming the erased patient.
 * </p>
 * <p>
 * Only that one case is waved through. Any other referrer is still a genuine
 * conflict and gets HAPI's own refusal, so a stray Task or CareTeam that the
 * deletion scope missed stops the erase instead of being silently orphaned.
 * </p>
 * <p>
 * The link rows themselves are not a problem: the generated SQL deletes
 * {@code HFJ_RES_LINK} by target as well as by source, so the rows pointing at
 * the patient go with it while their AuditEvents stay.
 * </p>
 */
public class DeleteExpungeSqlBuilderOverride extends DeleteExpungeSqlBuilder {

	public DeleteExpungeSqlBuilderOverride(ResourceTableFKProvider resourceTableFKProvider,
			JpaStorageSettings storageSettings, IIdHelperService<JpaPid> idHelper, IResourceLinkDao resourceLinkDao,
			PartitionSettings partitionSettings) {

		super(resourceTableFKProvider, storageSettings, idHelper, resourceLinkDao, partitionSettings);
	}

	@Override
	public void validateOkToDeleteAndExpunge(Set<JpaPid> pids, boolean cascade, Integer cascadeMaxRounds) {

		if (onlyRetainedAuditEventsRefer(pids)) {
			return;
		}
		super.validateOkToDeleteAndExpunge(pids, cascade, cascadeMaxRounds);
	}

	private boolean onlyRetainedAuditEventsRefer(Set<JpaPid> pids) {

		final List<JpaPid> targets = List.copyOf(pids);
		// HAPI collects into a synchronised list because the search fans out per partition.
		final List<ResourceLink> conflicts = Collections.synchronizedList(new ArrayList<>());
		findResourceLinksWithTargetPidIn(targets, targets, conflicts);

		return conflicts.stream()
				.allMatch(link -> AuditEvent.class.getSimpleName().equals(link.getSourceResourceType()));
	}
}
