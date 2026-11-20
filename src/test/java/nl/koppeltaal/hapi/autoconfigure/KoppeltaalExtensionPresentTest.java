package nl.koppeltaal.hapi.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Modifier;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * The starter instantiates a custom interceptor class itself when no bean of it exists. Without a public constructor
 * that fails, so the marker proves that the auto-configuration ran, not just that the jar is present.
 */
class KoppeltaalExtensionPresentTest {

  /** docker/config imports it, so the server does not start without the jar, whatever the custom-interceptor list holds. */
  @Test
  void shipsTheResourceTheConfigurationImports() {
    assertThat(KoppeltaalExtensionPresent.class.getResource("/nl/koppeltaal/hapi/extension.yaml")).isNotNull();
  }

  @Test
  void hasNoPublicConstructor() {
    assertThat(Arrays.stream(KoppeltaalExtensionPresent.class.getDeclaredConstructors())
      .noneMatch(constructor -> Modifier.isPublic(constructor.getModifiers()))).isTrue();
  }
}
