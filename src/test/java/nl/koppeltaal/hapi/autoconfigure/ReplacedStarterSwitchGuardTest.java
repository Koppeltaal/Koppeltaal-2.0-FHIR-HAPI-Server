package nl.koppeltaal.hapi.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.boot.env.SpringApplicationJsonEnvironmentPostProcessor;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.SimpleCommandLinePropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.SpringFactoriesLoader;
import org.springframework.mock.env.MockEnvironment;

/**
 * The starter keys of the features Koppeltaal replaces must not be set anywhere. On the fork they switched the Koppeltaal
 * features themselves, so a leftover would now be ignored. The guard refuses each one, says where it comes from and
 * names the key that replaces it.
 */
class ReplacedStarterSwitchGuardTest {

  private final ReplacedStarterSwitchGuard guard = new ReplacedStarterSwitchGuard();

  @Test
  void acceptsTheShippedConfigurationOfEveryEnvironment() throws IOException {
    for (String profile : List.of("staging", "prd")) {
      MockEnvironment environment = configuration("docker/config/application-" + profile + ".yaml",
        "docker/config/application.yaml");

      assertThatCode(() -> guard.postProcessEnvironment(environment, new SpringApplication()))
        .as(profile).doesNotThrowAnyException();
    }
  }

  /** On the fork, a profile or an additional file could switch the Koppeltaal cascade off with the starter key. */
  @Test
  void refusesTheCascadeSwitchInAConfigurationFile() {
    MockEnvironment environment = new MockEnvironment().withProperty("hapi.fhir.allow_cascading_deletes", "false");

    assertThatThrownBy(() -> guard.postProcessEnvironment(environment, new SpringApplication()))
      .hasMessageContaining("hapi.fhir.allow-cascading-deletes is set")
      .hasMessageContaining("koppeltaal.cascading-deletes.enabled");
  }

  @Test
  void refusesTheRepositoryValidationSwitchInAConfigurationFile() {
    MockEnvironment environment = new MockEnvironment()
      .withProperty("hapi.fhir.enable_repository_validating_interceptor", "false");

    assertThatThrownBy(() -> guard.postProcessEnvironment(environment, new SpringApplication()))
      .hasMessageContaining("koppeltaal.repository-validation.enabled");
  }

  /** The starter bound the guide map with relaxed names, so the dashed spelling counted as well. */
  @Test
  void refusesImplementationGuidesInTheDashedSpelling() {
    MockEnvironment environment = new MockEnvironment()
      .withProperty("hapi.fhir.implementation-guides.vendor.version", "1.0.0");

    assertThatThrownBy(() -> guard.postProcessEnvironment(environment, new SpringApplication()))
      .hasMessageContaining("koppeltaal.implementationguides");
  }

  /**
   * The underscore spelling of the guide map reads as hapi.fhir.implementation.guides. On the fork it overrode a guide that
   * a file declared in the dashed spelling, so it can be left behind when that file moves to koppeltaal.implementationguides.
   */
  @Test
  void refusesImplementationGuidesInTheUnderscoreSpellingFromTheEnvironment() {
    assertThatThrownBy(() -> guard.postProcessEnvironment(
      withEnvironment("HAPI_FHIR_IMPLEMENTATION_GUIDES_VENDOR_VERSION", "1.1.0"), new SpringApplication()))
      .hasMessageContaining("HAPI_FHIR_IMPLEMENTATION_GUIDES_VENDOR_VERSION")
      .hasMessageContaining("koppeltaal.implementationguides");
  }

  /** The fork switched CORS on when hapi.fhir.cors was present; the Koppeltaal settings need their own switch. */
  @Test
  void tellsThatTheCorsReplacementNeedsItsSwitch() {
    MockEnvironment environment = new MockEnvironment().withProperty("hapi.fhir.cors.allowed_origin[0]", "*");

    assertThatThrownBy(() -> guard.postProcessEnvironment(environment, new SpringApplication()))
      .hasMessageContaining("koppeltaal.cors.enabled: true");
  }

  @Test
  void refusesTheCascadeSwitchFromTheEnvironment() {
    assertThatThrownBy(() -> guard.postProcessEnvironment(withEnvironment("HAPI_FHIR_ALLOWCASCADINGDELETES", "false"),
      new SpringApplication()))
      .hasMessageContaining("hapi.fhir.allow-cascading-deletes")
      .hasMessageContaining("koppeltaal.cascading-deletes.enabled");
  }

  /** Spring also binds the underscore spelling, so the fork honoured it as well. The message names the variable. */
  @Test
  void refusesTheCascadeSwitchInTheUnderscoreSpelling() {
    assertThatThrownBy(() -> guard.postProcessEnvironment(withEnvironment("HAPI_FHIR_ALLOW_CASCADING_DELETES", "false"),
      new SpringApplication()))
      .hasMessageContaining("HAPI_FHIR_ALLOW_CASCADING_DELETES")
      .hasMessageContaining("koppeltaal.cascading-deletes.enabled");
  }

  /** The likeliest leftover: a variable that bumped the IG version on the fork. */
  @Test
  void refusesAnImplementationGuideVersionFromTheEnvironment() {
    assertThatThrownBy(() -> guard.postProcessEnvironment(
      withEnvironment("HAPI_FHIR_IMPLEMENTATIONGUIDES_KT2_VERSION", "0.16.7"), new SpringApplication()))
      .hasMessageContaining("hapi.fhir.implementationguides is set")
      .hasMessageContaining("HAPI_FHIR_IMPLEMENTATIONGUIDES_KT2_VERSION")
      .hasMessageContaining("koppeltaal.implementationguides");
  }

  /** The fork read this switch by its literal name, so this was the variable that took effect there. */
  @Test
  void refusesTheRepositoryValidationSwitchInTheSpellingTheForkRead() {
    assertThatThrownBy(() -> guard.postProcessEnvironment(
      withEnvironment("HAPI_FHIR_ENABLE_REPOSITORY_VALIDATING_INTERCEPTOR", "false"), new SpringApplication()))
      .hasMessageContaining("HAPI_FHIR_ENABLE_REPOSITORY_VALIDATING_INTERCEPTOR")
      .hasMessageContaining("koppeltaal.repository-validation.enabled");
  }

  @Test
  void refusesTheRepositoryValidationSwitchFromTheEnvironment() {
    assertThatThrownBy(() -> guard.postProcessEnvironment(
      withEnvironment("HAPI_FHIR_ENABLEREPOSITORYVALIDATINGINTERCEPTOR", "false"), new SpringApplication()))
      .hasMessageContaining("koppeltaal.repository-validation.enabled");
  }

  @Test
  void refusesStarterCorsFromASystemProperty() {
    MockEnvironment environment = new MockEnvironment();
    environment.getPropertySources().addFirst(new MapPropertySource(
      StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME, Map.of("hapi.fhir.cors.allowed_origin[0]", "*")));

    assertThatThrownBy(() -> guard.postProcessEnvironment(environment, new SpringApplication()))
      .hasMessageContaining("koppeltaal.cors");
  }

  @Test
  void refusesStarterImplementationGuidesFromTheCommandLine() {
    MockEnvironment environment = new MockEnvironment();
    environment.getPropertySources().addFirst(
      new SimpleCommandLinePropertySource("--hapi.fhir.implementationguides.kt2.name=koppeltaalv2.00"));

    assertThatThrownBy(() -> guard.postProcessEnvironment(environment, new SpringApplication()))
      .hasMessageContaining("koppeltaal.implementationguides");
  }

  /** Spring Boot turns SPRING_APPLICATION_JSON into its own property source, which outranks the environment. */
  @Test
  void refusesTheCascadeSwitchFromSpringApplicationJson() {
    MockEnvironment environment = withSpringApplicationJson("{\"hapi\":{\"fhir\":{\"allow_cascading_deletes\":false}}}");

    assertThatThrownBy(() -> guard.postProcessEnvironment(environment, new SpringApplication()))
      .hasMessageContaining("koppeltaal.cascading-deletes.enabled");
  }

  @Test
  void refusesAnEmptyGuideMapFromSpringApplicationJson() {
    MockEnvironment environment = withSpringApplicationJson("{\"hapi\":{\"fhir\":{\"implementationguides\":{}}}}");

    assertThatThrownBy(() -> guard.postProcessEnvironment(environment, new SpringApplication()))
      .hasMessageContaining("koppeltaal.implementationguides");
  }

  @Test
  void isRegisteredForEveryStartup() {
    assertThat(SpringFactoriesLoader.forDefaultResourceLocation(getClass().getClassLoader())
      .load(EnvironmentPostProcessor.class, null, (factoryType, implementationName, failure) -> {
      })).anyMatch(ReplacedStarterSwitchGuard.class::isInstance);
  }

  private static MockEnvironment withEnvironment(String name, String value) {
    MockEnvironment environment = new MockEnvironment();
    environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource(
      StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, Map.of(name, value)));
    return environment;
  }

  private static MockEnvironment withSpringApplicationJson(String json) {
    MockEnvironment environment = withEnvironment("SPRING_APPLICATION_JSON", json);
    new SpringApplicationJsonEnvironmentPostProcessor().postProcessEnvironment(environment, new SpringApplication());
    return environment;
  }

  /** The files in order of precedence, as Spring layers a profile file over the base file. */
  private static MockEnvironment configuration(String... paths) throws IOException {
    MockEnvironment environment = new MockEnvironment();
    for (String path : paths) {
      new YamlPropertySourceLoader().load(path, new FileSystemResource(path)).forEach(environment.getPropertySources()::addLast);
    }
    return environment;
  }
}
