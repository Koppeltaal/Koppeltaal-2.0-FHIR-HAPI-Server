package nl.koppeltaal.hapi.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;

import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * These HAPI features bypass the resource-origin checks of the ResourceOriginAuthorizationInterceptor, so they must
 * stay disabled. Cascading deletes are allowed, those are authorized per resource by the
 * ResourceOriginCascadingDeleteInterceptor. The test resources contain another application.yaml, hence the explicit
 * paths. The starter's switches for features Koppeltaal replaces stay off in every environment; WiringAssertions
 * refuses them at startup too.
 */
class SecurityRelevantConfigurationTest {

  private static final List<String> DISABLED_FEATURES = List.of(
    "hapi.fhir.expunge_enabled",
    "hapi.fhir.allow_multiple_delete"
  );

  @Test
  void shouldDisableFeaturesThatBypassTheResourceOriginChecks() {
    Properties configuration = load("src/main/resources/application.yaml");

    for (String feature : DISABLED_FEATURES) {
      assertEquals("false", configuration.getProperty(feature), feature + " must be false");
    }
  }

  @Test
  void shouldNotEnableThemInAnEnvironmentProfile() {
    for (String profile : List.of("src/main/resources/application-staging.yaml", "src/main/resources/application-prd.yaml")) {
      Properties configuration = load(profile);

      for (String feature : DISABLED_FEATURES) {
        assertNotEquals("true", configuration.getProperty(feature), feature + " must not be enabled in " + profile);
      }
    }
  }

  @Test
  void shouldLeaveTheStarterSwitchesOfReplacedFeaturesOffInEveryEnvironment() {
    for (String profile : List.of("staging", "prd")) {
      Properties configuration = loadAll("src/main/resources/application.yaml",
        "src/main/resources/application-" + profile + ".yaml");
      assertEquals("false", configuration.getProperty("hapi.fhir.allow_cascading_deletes"), profile);
      assertEquals("false", configuration.getProperty("hapi.fhir.enable_repository_validating_interceptor"), profile);
      assertTrue(configuration.keySet().stream().map(String::valueOf)
        .noneMatch(key -> key.startsWith("hapi.fhir.cors") || key.startsWith("hapi.fhir.implementationguides")), profile);
      assertEquals("true", configuration.getProperty("koppeltaal.cascading-deletes.enabled"), profile);
      assertEquals("true", configuration.getProperty("koppeltaal.cors.enabled"), profile);
      assertEquals("true", configuration.getProperty("koppeltaal.repository-validation.enabled"), profile);
      assertEquals("nl.koppeltaal.hapi.autoconfigure.KoppeltaalExtensionPresent",
        configuration.getProperty("hapi.fhir.custom-interceptor-classes"), profile);
      assertEquals("UUID", configuration.getProperty("hapi.fhir.server_id_strategy"), profile);
    }
  }

  /** Later files override earlier ones, as Spring layers a profile file over the base file. */
  private static Properties loadAll(String... paths) {
    YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
    factory.setResources(Stream.of(paths).map(FileSystemResource::new).toArray(Resource[]::new));
    return factory.getObject();
  }

  private static Properties load(String path) {
    YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
    factory.setResources(new FileSystemResource(path));
    return factory.getObject();
  }
}
