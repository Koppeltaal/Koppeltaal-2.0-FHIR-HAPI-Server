package nl.koppeltaal.hapi.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.mock.env.MockEnvironment;

/** The starter bound hapi.fhir.allow_cascading_deletes as a Boolean; the Koppeltaal switch takes the same values. */
class CascadingDeletesSwitchTest {

  @ParameterizedTest
  @ValueSource(strings = {"true", "TRUE", "on", "yes", "1"})
  void switchesTheCascadeOn(String value) {
    assertThat(matches(new MockEnvironment().withProperty("koppeltaal.cascading-deletes.enabled", value))).isTrue();
  }

  @ParameterizedTest
  @ValueSource(strings = {"false", "off", "no", "0"})
  void switchesTheCascadeOff(String value) {
    assertThat(matches(new MockEnvironment().withProperty("koppeltaal.cascading-deletes.enabled", value))).isFalse();
  }

  @Test
  void leavesTheCascadeOffWithoutTheSwitch() {
    assertThat(matches(new MockEnvironment())).isFalse();
  }

  @Test
  void stopsTheStartupOnAValueThatIsNoBoolean() {
    assertThatThrownBy(() -> matches(new MockEnvironment().withProperty("koppeltaal.cascading-deletes.enabled", "maybe")))
      .isInstanceOf(BindException.class);
  }

  /** K8S secrets arrive as environment variables. */
  @Test
  void readsTheEnvironmentVariable() {
    MockEnvironment environment = new MockEnvironment();
    environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource(
      StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, Map.of("KOPPELTAAL_CASCADINGDELETES_ENABLED", "on")));

    assertThat(matches(environment)).isTrue();
  }

  private static boolean matches(Environment environment) {
    ConditionContext context = mock(ConditionContext.class);
    when(context.getEnvironment()).thenReturn(environment);
    return new StarterReplacementConfig.CascadingDeletesEnabled().matches(context, null);
  }
}
