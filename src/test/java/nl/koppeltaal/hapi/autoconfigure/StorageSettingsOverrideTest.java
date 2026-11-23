package nl.koppeltaal.hapi.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import ca.uhn.fhir.jpa.api.config.JpaStorageSettings;
import ca.uhn.fhir.jpa.starter.Application;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * The fork's code set UUID resource ids and full-text indexing itself, so configuration could not change them. Command-line
 * arguments outrank the configuration, as environment variables outrank the deployed yaml.
 */
class StorageSettingsOverrideTest {

  @Test
  void keepsUuidIdsAndFullTextIndexingWhateverTheConfigurationSays() {
    try (ConfigurableApplicationContext context = new SpringApplicationBuilder(Application.class).run(
      "--spring.datasource.url=jdbc:h2:mem:storage-settings-override-" + System.nanoTime(), "--server.port=0",
      "--hapi.fhir.fhir_version=r4", "--hapi.fhir.cr_enabled=false", "--hapi.fhir.mdm_enabled=false",
      "--hapi.fhir.subscription.websocket_enabled=false",
      "--hapi.fhir.server_id_strategy=SEQUENTIAL_NUMERIC", "--hapi.fhir.search_index_full_text_enabled=false")) {
      JpaStorageSettings settings = context.getBean(JpaStorageSettings.class);

      assertThat(settings.getResourceServerIdStrategy()).isEqualTo(JpaStorageSettings.IdStrategyEnum.UUID);
      assertThat(settings.isHibernateSearchIndexFullText()).isTrue();
    }
  }
}
