package ca.uhn.fhir.jpa.starter.koppeltaal.cleanup;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r4.model.IdType;

/**
 * Plans ownership-based erasure and updates to retained resources without mutating either. A blocked
 * resource stays behind, unchanged, for manual review; the patient and the rest of its scope still go.
 */
public class PatientDeletionScope {

	public record Blocker(IIdType resourceId, String blockReason) { }
	public record ParticipantRemoval(IIdType careTeamId, Set<String> references) {
		public ParticipantRemoval { references = Set.copyOf(references); }
	}
	public record RetainedTaskOwner(IIdType taskId, String patientReference) { }
	public record Plan(List<IIdType> resources, List<ParticipantRemoval> detachments,
			List<RetainedTaskOwner> retainedTaskOwners, List<Blocker> blockers) {
		public Plan {
			resources = List.copyOf(resources);
			detachments = List.copyOf(detachments);
			retainedTaskOwners = List.copyOf(retainedTaskOwners);
			blockers = List.copyOf(blockers);
		}
	}

	private static final int MAX_RESOURCES = 2_000;

	/* Everything whose roles can change a patient's plan: what names the patient now or in an earlier
	 * version, Tasks owned by such a CareTeam or RelatedPerson, and the owners and parents of those.
	 * History is matched as text before it is parsed; HAPI stores references verbatim in its JSON. */
	private static final String SOURCES = """
			WITH anchor AS (SELECT res_id FROM hfj_resource WHERE res_type='Patient' AND fhir_id=:patientId),
			named AS MATERIALIZED (
			  SELECT l.src_resource_id AS res_id FROM hfj_res_link l
			  WHERE l.target_resource_id=(SELECT res_id FROM anchor)
			    AND l.src_path IN ('Task.for','CareTeam.subject','RelatedPerson.patient')
			  UNION SELECT r.res_id FROM hfj_resource r JOIN hfj_res_ver v ON v.res_id=r.res_id
			  WHERE EXISTS (SELECT 1 FROM anchor) AND r.res_type IN ('Task','CareTeam','RelatedPerson')
			    AND v.res_encoding='JSON' AND strpos(v.res_text_vc, 'Patient/' || :patientId) > 0
			), actors AS MATERIALIZED (
			  SELECT r.res_id, r.res_type || '/' || r.fhir_id AS reference
			  FROM hfj_resource r JOIN named n ON n.res_id=r.res_id
			  WHERE r.res_type IN ('CareTeam','RelatedPerson')
			), scope AS MATERIALIZED (
			  SELECT res_id FROM named
			  UNION SELECT l.src_resource_id FROM hfj_res_link l JOIN actors a ON a.res_id=l.target_resource_id
			  WHERE l.src_path='Task.owner'
			  UNION SELECT r.res_id FROM hfj_resource r JOIN hfj_res_ver v ON v.res_id=r.res_id
			  WHERE EXISTS (SELECT 1 FROM actors) AND r.res_type='Task' AND v.res_encoding='JSON'
			    AND EXISTS (SELECT 1 FROM actors a WHERE strpos(v.res_text_vc, a.reference) > 0)
			)
			SELECT res_id FROM scope
			UNION SELECT l.target_resource_id FROM hfj_res_link l JOIN scope s ON s.res_id=l.src_resource_id
			WHERE l.src_path IN ('Task.owner','Task.partOf') AND l.target_resource_id IS NOT NULL
			UNION SELECT owner.res_id FROM scope s JOIN hfj_res_ver v ON v.res_id=s.res_id
			JOIN hfj_resource owner ON owner.res_type=split_part(v.res_text_vc::jsonb #>> '{owner,reference}','/',1)
			  AND owner.fhir_id=split_part(v.res_text_vc::jsonb #>> '{owner,reference}','/',2)
			  AND owner.res_type || '/' || owner.fhir_id=regexp_replace(v.res_text_vc::jsonb #>> '{owner,reference}', '/_history/[^/]+$', '')
			WHERE v.res_encoding='JSON' AND v.res_text_vc IS NOT NULL
			""";

	/* Ownership includes previous subjects and patient-bound CareTeam/RelatedPerson Task owners.
	 * A direct Patient owner denotes execution responsibility, not beneficiary ownership - unless no version
	 * of the Task has a Task.for and every version names this patient as owner: then it names only this patient.
	 * Deleted owners use their last non-deletion version; historical CareTeam membership may be retained.
	 * Roles are built for :sources only; parsing the history of every Task costs seconds per query.
	 * actors and selected are MATERIALIZED: when inlined, the planner reruns them inside nested loops. */
	private static final String OWNERSHIP = """
			WITH anchor AS (SELECT res_id FROM hfj_resource WHERE res_type='Patient' AND fhir_id=:patientId),
			sources AS MATERIALIZED (SELECT unnest(string_to_array(:sources, ',')::bigint[]) AS res_id),
			historical_roles AS (
			  SELECT DISTINCT r.res_id AS source_id, e.path, target.res_id AS target_id
			  FROM hfj_resource r JOIN hfj_res_ver v ON v.res_id=r.res_id
			  CROSS JOIN LATERAL (VALUES
			    (r.res_type || CASE r.res_type WHEN 'Task' THEN '.for' WHEN 'CareTeam' THEN '.subject' ELSE '.patient' END,
			     v.res_text_vc::jsonb -> CASE r.res_type WHEN 'Task' THEN 'for' WHEN 'CareTeam' THEN 'subject' ELSE 'patient' END ->> 'reference'),
			    ('Task.owner', CASE WHEN r.res_type='Task' THEN v.res_text_vc::jsonb #>> '{owner,reference}' END)
			  ) e(path, reference)
			  JOIN hfj_resource target ON target.res_type=split_part(e.reference,'/',1)
			    AND target.fhir_id=split_part(e.reference,'/',2)
			    AND target.res_type || '/' || target.fhir_id =
			    regexp_replace(e.reference, '/_history/[^/]+$', '')
			  WHERE r.res_id IN (SELECT res_id FROM sources) AND r.res_type IN ('Task','CareTeam','RelatedPerson')
			    AND v.res_encoding='JSON' AND v.res_text_vc IS NOT NULL
			), roles AS (
			  SELECT l.src_resource_id AS source_id, l.src_path AS path, l.target_resource_id AS target_id
			  FROM hfj_res_link l JOIN hfj_resource r ON r.res_id=l.src_resource_id
			  WHERE l.src_resource_id IN (SELECT res_id FROM sources)
			    AND r.res_deleted_at IS NULL AND r.res_type IN ('Task','CareTeam','RelatedPerson')
			    AND l.src_path IN ('Task.for','Task.owner','CareTeam.subject','RelatedPerson.patient')
			  UNION SELECT source_id,path,target_id FROM historical_roles
			), subjects AS (
			  SELECT DISTINCT source_id,target_id FROM roles
			  WHERE path IN ('Task.for','CareTeam.subject','RelatedPerson.patient')
			), patient_owners AS (
			  SELECT owner.res_id AS source_id,l.target_resource_id AS target_id
			  FROM hfj_resource owner JOIN hfj_res_link l ON l.src_resource_id=owner.res_id
			    AND l.src_path=CASE owner.res_type WHEN 'CareTeam' THEN 'CareTeam.subject' ELSE 'RelatedPerson.patient' END
			  JOIN hfj_resource patient ON patient.res_id=l.target_resource_id AND patient.res_type='Patient'
			  WHERE owner.res_id IN (SELECT res_id FROM sources)
			    AND owner.res_type IN ('CareTeam','RelatedPerson') AND owner.res_deleted_at IS NULL
			  UNION
			  SELECT owner.res_id,patient.res_id
			  FROM hfj_resource owner JOIN LATERAL (
			    SELECT CASE WHEN v.res_encoding='JSON' THEN v.res_text_vc::jsonb END
			      -> CASE owner.res_type WHEN 'CareTeam' THEN 'subject' ELSE 'patient' END ->> 'reference' AS reference
			    FROM hfj_res_ver v WHERE v.res_id=owner.res_id AND v.res_encoding<>'DEL'
			    ORDER BY v.res_ver DESC LIMIT 1
			  ) last_version ON true
			  JOIN hfj_resource patient ON patient.res_type='Patient'
			    AND patient.fhir_id=split_part(last_version.reference,'/',2)
			    AND patient.res_type || '/' || patient.fhir_id=regexp_replace(last_version.reference, '/_history/[^/]+$', '')
			  WHERE owner.res_id IN (SELECT res_id FROM sources)
			    AND owner.res_type IN ('CareTeam','RelatedPerson') AND owner.res_deleted_at IS NOT NULL
			), owner_only AS (
			  SELECT r.res_id FROM hfj_resource r JOIN hfj_res_ver v ON v.res_id=r.res_id
			  WHERE r.res_id IN (SELECT res_id FROM sources) AND r.res_type='Task' AND v.res_encoding<>'DEL'
			    AND EXISTS (SELECT 1 FROM anchor)
			  GROUP BY r.res_id
			  HAVING bool_and(COALESCE(CASE WHEN v.res_encoding='JSON' THEN v.res_text_vc::jsonb -> 'for' IS NULL
			    AND regexp_replace(v.res_text_vc::jsonb #>> '{owner,reference}', '/_history/[^/]+$', '')='Patient/' || :patientId
			  END, false))
			), actors AS MATERIALIZED (
			  SELECT r.res_id FROM hfj_resource r JOIN subjects s ON s.source_id=r.res_id
			  WHERE r.res_type IN ('CareTeam','RelatedPerson') AND s.target_id=(SELECT res_id FROM anchor)
			), selected AS MATERIALIZED (
			  SELECT source_id AS res_id FROM subjects WHERE target_id=(SELECT res_id FROM anchor)
			  UNION SELECT source_id FROM roles WHERE path='Task.owner' AND target_id IN (SELECT res_id FROM actors)
			  UNION SELECT res_id FROM owner_only
			  UNION SELECT res_id FROM anchor
			)
			""";

	private final EntityManager entityManager;

	public PatientDeletionScope(EntityManager entityManager) { this.entityManager = entityManager; }

	public Plan planFor(IIdType patientId) {
		String patient = patientId.getIdPart();
		String sources = sourcesFor(patient);
		List<Blocker> blockers = new ArrayList<>(blockersFor(patient, sources));
		Set<String> blocked = blockers.stream().map(blocker -> blocker.resourceId().getValue()).collect(Collectors.toSet());
		List<IIdType> resources = resourcesFor(patient, sources).stream().filter(id -> !blocked.contains(id.getValue())).toList();
		Map<String, Set<String>> removals = new LinkedHashMap<>();
		Map<String, RetainedTaskOwner> retainedTaskOwners = new LinkedHashMap<>();
		for (Object[] row : rows(OWNERSHIP + """
				SELECT DISTINCT source.res_type,source.fhir_id,l.src_path,target.res_type,target.fhir_id,
				  EXISTS (SELECT 1 FROM hfj_res_link beneficiary
				    JOIN hfj_resource patient ON patient.res_id=beneficiary.target_resource_id AND patient.res_type='Patient'
				    WHERE beneficiary.src_resource_id=source.res_id AND beneficiary.src_path='Task.for'
				      AND patient.res_id<>(SELECT res_id FROM anchor)) AS retained_beneficiary
				FROM hfj_res_link l JOIN hfj_resource source ON source.res_id=l.src_resource_id
				JOIN hfj_resource target ON target.res_id=l.target_resource_id
				WHERE l.target_resource_id IN (SELECT res_id FROM selected)
				  AND l.src_resource_id NOT IN (SELECT res_id FROM selected)
				  AND source.res_type<>'AuditEvent'
				  AND source.res_deleted_at IS NULL
				ORDER BY source.res_type,source.fhir_id,l.src_path,target.res_type,target.fhir_id
				""", patient, sources)) {
			String source = row[0] + "/" + row[1];
			String target = row[3] + "/" + row[4];
			// A blocked resource is not erased, so whatever references it keeps a valid reference.
			if (blocked.contains(target)) continue;
			if ("CareTeam".equals(row[0]) && "CareTeam.participant.member".equals(row[2])
					&& Set.of("Patient", "RelatedPerson", "CareTeam").contains(row[3])) {
				removals.computeIfAbsent(source, key -> new LinkedHashSet<>()).add(target);
			} else if ("Task".equals(row[0]) && "Task.owner".equals(row[2])
					&& "Patient".equals(row[3]) && Boolean.TRUE.equals(row[5])) {
				retainedTaskOwners.put(source, new RetainedTaskOwner(new IdType(source), target));
			} else {
				blockers.add(new Blocker(new IdType(source),
						"Resource is outside the deletion scope but references resources selected for erasure: "
								+ row[2] + " references " + target + ". Kept for manual review with that reference dangling."));
			}
		}
		List<ParticipantRemoval> detachments = removals.entrySet().stream()
				.map(entry -> new ParticipantRemoval(new IdType(entry.getKey()), entry.getValue())).toList();
		return new Plan(resources, detachments, List.copyOf(retainedTaskOwners.values()), mergeBlockers(blockers));
	}

	public List<IIdType> resourcesFor(IIdType patientId) {
		return resourcesFor(patientId.getIdPart(), sourcesFor(patientId.getIdPart()));
	}

	private List<IIdType> resourcesFor(String patient, String sources) {
		List<IIdType> result = rows(OWNERSHIP + """
				SELECT r.res_type,r.fhir_id FROM hfj_resource r JOIN selected s ON s.res_id=r.res_id
				ORDER BY CASE WHEN r.res_type='Patient' THEN 1 ELSE 0 END,r.res_type,r.fhir_id
				""", patient, sources).stream().map(row -> (IIdType) new IdType((String) row[0], (String) row[1])).toList();
		if (result.size() > MAX_RESOURCES) {
			throw new IllegalStateException("Patient/" + patient + " has " + result.size()
					+ " resources in scope, over the " + MAX_RESOURCES + " this run will erase unattended");
		}
		return result;
	}

	/** Check both the beneficiary and the owner's patient, whichever side selected the Task. */
	public List<Blocker> blockersFor(IIdType patientId) {
		return blockersFor(patientId.getIdPart(), sourcesFor(patientId.getIdPart()));
	}

	private List<Blocker> blockersFor(String patient, String sources) {
		List<Blocker> result = new ArrayList<>();
		for (Object[] row : rows(OWNERSHIP + """
				SELECT DISTINCT task.fhir_id,beneficiary.fhir_id,owner.res_type,owner.fhir_id,owner_patient.fhir_id
				FROM selected chosen JOIN hfj_resource task ON task.res_id=chosen.res_id AND task.res_type='Task'
				LEFT JOIN subjects task_subject ON task_subject.source_id=task.res_id
				LEFT JOIN hfj_resource beneficiary ON beneficiary.res_id=task_subject.target_id
				JOIN roles owning ON owning.source_id=task.res_id AND owning.path='Task.owner'
				JOIN hfj_resource owner ON owner.res_id=owning.target_id AND owner.res_type IN ('CareTeam','RelatedPerson')
				LEFT JOIN subjects owner_subject ON owner_subject.source_id=owner.res_id AND owner.res_type IN ('CareTeam','RelatedPerson')
				JOIN hfj_resource owner_patient ON owner_patient.res_id=owner_subject.target_id
				WHERE COALESCE(beneficiary.res_id,(SELECT res_id FROM anchor))<>owner_patient.res_id
				""", patient, sources)) {
			String beneficiary = row[1] == null ? "Erasure of Patient/" + patient : "Task.for references Patient/" + row[1];
			result.add(new Blocker(new IdType("Task", (String) row[0]),
					"Conflicting patient assignments: " + beneficiary
							+ ", but Task.owner references " + row[2] + "/" + row[3]
							+ " associated with Patient/" + row[4] + ". Kept for manual review."));
		}
		for (Object[] row : rows(OWNERSHIP + """
				SELECT owner.res_type,owner.fhir_id FROM selected chosen
				JOIN hfj_resource owner ON owner.res_id=chosen.res_id AND owner.res_type IN ('CareTeam','RelatedPerson')
				WHERE NOT EXISTS (SELECT 1 FROM patient_owners p WHERE p.source_id=owner.res_id AND p.target_id=(SELECT res_id FROM anchor))
				""", patient, sources)) {
			String path = "CareTeam".equals(row[0]) ? "CareTeam.subject" : "RelatedPerson.patient";
			result.add(new Blocker(new IdType((String) row[0], (String) row[1]),
					"The latest " + path + " does not establish patient ownership for Patient/" + patient
							+ ". Erasing all versions could delete shared or another patient's data."));
		}
		for (Object[] row : rows(OWNERSHIP + """
				SELECT task.fhir_id,v.res_ver FROM selected chosen
				JOIN hfj_resource task ON task.res_id=chosen.res_id AND task.res_type='Task'
				JOIN hfj_res_ver v ON v.res_id=task.res_id
				WHERE v.res_encoding='JSON' AND v.res_text_vc IS NOT NULL
				  AND v.res_text_vc::jsonb #>> '{for,reference}' IS NULL
				  AND v.res_text_vc::jsonb #>> '{owner,reference}' IS NOT NULL
				  AND NOT EXISTS (
				    SELECT 1 FROM hfj_resource owner
				    WHERE owner.res_type=split_part(v.res_text_vc::jsonb #>> '{owner,reference}','/',1)
				      AND owner.fhir_id=split_part(v.res_text_vc::jsonb #>> '{owner,reference}','/',2)
				      AND owner.res_type || '/' || owner.fhir_id=regexp_replace(v.res_text_vc::jsonb #>> '{owner,reference}', '/_history/[^/]+$', '')
				      AND (owner.res_id=(SELECT res_id FROM anchor) OR (owner.res_type<>'Patient' AND NOT EXISTS (
				        SELECT 1 FROM patient_owners p WHERE p.source_id=owner.res_id AND p.target_id<>(SELECT res_id FROM anchor))))
				  )
				""", patient, sources)) {
			result.add(new Blocker(new IdType("Task", (String) row[0]),
					"Version " + row[1] + " has no Task.for and its Task.owner names another patient or does not resolve. "
							+ "Erasing all versions could delete another patient's data."));
		}
		for (Object[] row : rows(OWNERSHIP + """
				SELECT DISTINCT child.fhir_id,parent.fhir_id,parent_patient.fhir_id
				FROM selected chosen JOIN hfj_resource child ON child.res_id=chosen.res_id AND child.res_type='Task'
				JOIN hfj_res_link part ON part.src_resource_id=child.res_id AND part.src_path='Task.partOf'
				JOIN hfj_resource parent ON parent.res_id=part.target_resource_id
				JOIN subjects parent_subject ON parent_subject.source_id=parent.res_id
				JOIN hfj_resource parent_patient ON parent_patient.res_id=parent_subject.target_id
				WHERE parent_subject.target_id<>(SELECT res_id FROM anchor)
				""", patient, sources)) {
			result.add(new Blocker(new IdType("Task", (String) row[0]),
					"Task.partOf references Task/" + row[1] + " belonging to Patient/" + row[2]
							+ ", outside Patient/" + patient + " being erased. Kept for manual review."));
		}
		// Check the ownership of old versions too; do not erase another patient's history.
		for (Object[] row : rows(OWNERSHIP + """
				SELECT r.res_type,r.fhir_id,v.res_ver,
				       v.res_text_vc::jsonb -> CASE r.res_type WHEN 'Task' THEN 'for' WHEN 'CareTeam' THEN 'subject' ELSE 'patient' END ->> 'reference'
				FROM selected chosen JOIN hfj_resource r ON r.res_id=chosen.res_id
				JOIN hfj_res_ver v ON v.res_id=r.res_id
				WHERE r.res_type IN ('Task','CareTeam','RelatedPerson') AND v.res_encoding='JSON' AND v.res_text_vc IS NOT NULL
				""", patient, sources)) {
			if (row[3] == null) continue;
			IdType subject = new IdType(row[3].toString());
			if (subject.hasBaseUrl() || !"Patient".equals(subject.getResourceType()) || !patient.equals(subject.getIdPart())) {
				result.add(new Blocker(new IdType((String) row[0], (String) row[1]),
						"Version " + row[2] + " has patient ownership reference " + row[3]
								+ ", outside Patient/" + patient + ". Erasing all versions could delete another patient's data."));
			}
		}
		return mergeBlockers(result);
	}

	private List<Blocker> mergeBlockers(List<Blocker> blockers) {
		Map<String, Set<String>> reasons = new LinkedHashMap<>();
		blockers.forEach(blocker -> reasons.computeIfAbsent(blocker.resourceId().getValue(), key -> new LinkedHashSet<>())
				.add(blocker.blockReason()));
		return reasons.entrySet().stream().map(entry -> new Blocker(new IdType(entry.getKey()),
				String.join(" ", entry.getValue()))).toList();
	}

	/** Comma-separated, so every OWNERSHIP query takes the ids as a single parameter. */
	private String sourcesFor(String patient) {
		List<?> ids = entityManager.createNativeQuery(SOURCES).setParameter("patientId", patient).getResultList();
		return ids.stream().map(String::valueOf).collect(Collectors.joining(","));
	}

	@SuppressWarnings("unchecked")
	private List<Object[]> rows(String sql, String patient, String sources) {
		Query query = entityManager.createNativeQuery(sql).setParameter("patientId", patient)
				.setParameter("sources", sources);
		return query.getResultList();
	}

	public static IIdType patientId(String idPart) { return new IdType("Patient", idPart); }
}
