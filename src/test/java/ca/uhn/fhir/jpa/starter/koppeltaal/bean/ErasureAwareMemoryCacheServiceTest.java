package ca.uhn.fhir.jpa.starter.koppeltaal.bean;

import static org.assertj.core.api.Assertions.assertThat;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.interceptor.model.RequestPartitionId;
import ca.uhn.fhir.jpa.api.config.JpaStorageSettings;
import ca.uhn.fhir.jpa.util.MemoryCacheService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class ErasureAwareMemoryCacheServiceTest {

	private final MemoryCacheService cache = new ErasureAwareMemoryCacheService(new JpaStorageSettings(), FhirContext.forR4Cached());

	@ParameterizedTest
	@NullSource
	@ValueSource(strings = {"Patient", "Task", "CareTeam", "RelatedPerson"})
	void aLateReaderCannotRestoreAUsableLookupForAnErasableResource(String type) {
		var key = new MemoryCacheService.ForcedIdCacheKey(type, "example", RequestPartitionId.allPartitions());
		cache.invalidateCaches(MemoryCacheService.CacheEnum.RESOURCE_LOOKUP_BY_FORCED_ID);
		cache.put(MemoryCacheService.CacheEnum.RESOURCE_LOOKUP_BY_FORCED_ID, key, "PID read before erasure");

		assertThat((Object) cache.getIfPresent(MemoryCacheService.CacheEnum.RESOURCE_LOOKUP_BY_FORCED_ID, key)).isNull();
	}

	@Test
	void retainedAuditAndPractitionerLookupsStillUseTheCache() {
		for (String type : java.util.List.of("AuditEvent", "Practitioner")) {
			var key = new MemoryCacheService.ForcedIdCacheKey(type, "example", RequestPartitionId.allPartitions());
			cache.put(MemoryCacheService.CacheEnum.RESOURCE_LOOKUP_BY_FORCED_ID, key, "retained PID");

			assertThat((Object) cache.getIfPresent(MemoryCacheService.CacheEnum.RESOURCE_LOOKUP_BY_FORCED_ID, key)).isEqualTo("retained PID");
		}
	}
}
