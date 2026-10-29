package ca.uhn.fhir.jpa.starter.koppeltaal.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.FileSystemResource;

import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * These HAPI features bypass the resource-origin checks of the ResourceOriginAuthorizationInterceptor, so they must
 * stay disabled. Cascading deletes are allowed, those are authorized per resource by the
 * ResourceOriginCascadingDeleteInterceptor. The test resources contain another application.yaml, hence the explicit
 * paths.
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

  private static Properties load(String path) {
    YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
    factory.setResources(new FileSystemResource(path));
    return factory.getObject();
  }
}
