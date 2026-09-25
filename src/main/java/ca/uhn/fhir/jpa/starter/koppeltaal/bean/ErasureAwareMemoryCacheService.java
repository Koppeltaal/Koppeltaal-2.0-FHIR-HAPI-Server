package ca.uhn.fhir.jpa.starter.koppeltaal.bean;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.api.config.JpaStorageSettings;
import ca.uhn.fhir.jpa.util.MemoryCacheService;
import java.util.Set;

/** Prevents stale identity lookups after another transaction or server has erased a resource. */
public class ErasureAwareMemoryCacheService extends MemoryCacheService {

	private static final Set<String> ERASABLE_TYPES = Set.of("Patient", "Task", "CareTeam", "RelatedPerson");
	private final FhirContext fhirContext;

	public ErasureAwareMemoryCacheService(JpaStorageSettings settings, FhirContext fhirContext) {
		super(settings);
		this.fhirContext = fhirContext;
	}

	@Override
	protected <K, V> V doGetIfPresent(CacheEnum cache, K key) {
		if (cache == CacheEnum.RESOURCE_LOOKUP_BY_FORCED_ID && key instanceof ForcedIdCacheKey forcedId) {
			String type = forcedId.toIdType(fhirContext).getResourceType();
			// Eviction cannot prevent an older reader's afterCommit from restoring
			// a stale PID, nor can it reach another JVM. Resolve erasable identities
			// from the database, including untyped references that might name one.
			if (type == null || ERASABLE_TYPES.contains(type)) return null;
		}
		return super.doGetIfPresent(cache, key);
	}
}
