package nl.koppeltaal.hapi.autoconfigure;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ca.uhn.fhir.jpa.starter.Application;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;

/**
 * A starter switch for a feature that Koppeltaal replaces must stop the server, also when it comes from an environment
 * variable. Command-line arguments outrank the test yaml, as environment variables outrank the deployed yaml.
 */
class StarterSwitchGuardTest {

  @Test
  void refusesTheStartersOwnCascadingDeletes() {
    assertThatThrownBy(() -> start("--hapi.fhir.allow_cascading_deletes=true"))
      .hasStackTraceContaining("hapi.fhir.allow_cascading_deletes must be false");
  }

  /** Production's combination: our cascade switch is on in the yaml, and a variable switches the starter's on too. */
  @Test
  void refusesTheStartersCascadingDeletesNextToOurs() {
    assertThatThrownBy(() -> start("--koppeltaal.cascading-deletes.enabled=true", "--hapi.fhir.allow_cascading_deletes=true"))
      .hasStackTraceContaining("hapi.fhir.allow_cascading_deletes must be false");
  }

  @Test
  void refusesTheStartersOwnCors() {
    assertThatThrownBy(() -> start("--hapi.fhir.cors.allowed_origin[0]=*"))
      .hasStackTraceContaining("hapi.fhir.cors must not be set");
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
