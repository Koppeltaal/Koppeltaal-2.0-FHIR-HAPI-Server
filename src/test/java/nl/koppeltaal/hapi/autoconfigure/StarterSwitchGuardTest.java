package nl.koppeltaal.hapi.autoconfigure;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ca.uhn.fhir.jpa.starter.Application;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;

/**
 * A starter key for a feature that Koppeltaal replaces must stop the server, wherever it is set and whatever its value.
 * Command-line arguments stand in for environment variables; an additional configuration file stands in for a profile
 * file or a replaced deployment file. ReplacedStarterSwitchGuard refuses both before the application context exists,
 * so these boots prove that it runs after Spring Boot has loaded the configuration files.
 */
class StarterSwitchGuardTest {

  @TempDir
  Path configurationDirectory;

  @Test
  void refusesTheStartersOwnCascadingDeletes() {
    assertThatThrownBy(() -> start("--hapi.fhir.allow_cascading_deletes=true"))
      .hasStackTraceContaining("hapi.fhir.allow-cascading-deletes is set")
      .hasStackTraceContaining("koppeltaal.cascading-deletes.enabled");
  }

  /** Production's combination: our cascade switch is on in the yaml, and a variable switches the starter's on too. */
  @Test
  void refusesTheStartersCascadingDeletesNextToOurs() {
    assertThatThrownBy(() -> start("--koppeltaal.cascading-deletes.enabled=true", "--hapi.fhir.allow_cascading_deletes=true"))
      .hasStackTraceContaining("hapi.fhir.allow-cascading-deletes is set")
      .hasStackTraceContaining("koppeltaal.cascading-deletes.enabled");
  }

  @Test
  void refusesTheStartersOwnCors() {
    assertThatThrownBy(() -> start("--hapi.fhir.cors.allowed_origin[0]=*"))
      .hasStackTraceContaining("hapi.fhir.cors is set")
      .hasStackTraceContaining("koppeltaal.cors");
  }

  /** On the fork this switched the Koppeltaal cascade off; the Koppeltaal switch would now leave it on. */
  @Test
  void refusesTheCascadeSwitchedOffInAConfigurationFile() {
    assertThatThrownBy(() -> startWithConfigurationFile("""
      koppeltaal.cascading-deletes.enabled: true
      hapi.fhir.allow_cascading_deletes: false
      """))
      .hasStackTraceContaining("hapi.fhir.allow-cascading-deletes is set")
      .hasStackTraceContaining("deployed.yaml")
      .hasStackTraceContaining("koppeltaal.cascading-deletes.enabled");
  }

  @Test
  void refusesTheStartersCascadingDeletesNextToOursInAConfigurationFile() {
    assertThatThrownBy(() -> startWithConfigurationFile("""
      koppeltaal.cascading-deletes.enabled: true
      hapi.fhir.allow_cascading_deletes: true
      """)).hasStackTraceContaining("hapi.fhir.allow-cascading-deletes is set");
  }

  @Test
  void refusesTheStartersCorsInAConfigurationFile() {
    assertThatThrownBy(() -> startWithConfigurationFile("""
      hapi.fhir.cors.allowed_origin: ["*"]
      """)).hasStackTraceContaining("hapi.fhir.cors is set");
  }

  @Test
  void refusesTheStartersRepositoryValidationInAConfigurationFile() {
    assertThatThrownBy(() -> startWithConfigurationFile("""
      hapi.fhir.enable_repository_validating_interceptor: false
      """)).hasStackTraceContaining("hapi.fhir.enable-repository-validating-interceptor is set");
  }

  /** The package URL is local, so a test that gets past the guard cannot reach a registry. */
  @Test
  void refusesTheStartersImplementationGuidesInAConfigurationFile() {
    assertThatThrownBy(() -> startWithConfigurationFile("""
      hapi.fhir.implementationguides.other:
        name: example.fhir.other
        version: 1.0.0
        packageUrl: file:%s
      """.formatted(configurationDirectory.resolve("absent.tgz"))))
      .hasStackTraceContaining("hapi.fhir.implementationguides is set")
      .hasStackTraceContaining("koppeltaal.implementationguides");
  }

  private void startWithConfigurationFile(String yaml) throws IOException {
    Path file = Files.writeString(configurationDirectory.resolve("deployed.yaml"), yaml);
    start("--spring.config.additional-location=file:" + file.toAbsolutePath());
  }

  private static void start(String... switches) {
    List<String> arguments = new ArrayList<>(List.of(
      "--spring.datasource.url=jdbc:h2:mem:starter-switch-guard-" + System.nanoTime(), "--server.port=0",
      "--hapi.fhir.fhir_version=r4", "--hapi.fhir.cr_enabled=false", "--hapi.fhir.mdm_enabled=false",
      "--hapi.fhir.subscription.websocket_enabled=false"));
    arguments.addAll(List.of(switches));
    new SpringApplicationBuilder(Application.class).run(arguments.toArray(String[]::new)).close();
  }
}
