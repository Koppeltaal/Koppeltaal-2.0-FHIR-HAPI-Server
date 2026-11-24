package nl.koppeltaal.hapi.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URL;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.support.SpringFactoriesLoader;
import org.springframework.mock.env.MockEnvironment;

/**
 * With the extension jar present, the Koppeltaal auto-configuration must run. The guard refuses every way to keep it
 * from running, whatever hapi.fhir.custom-interceptor-classes holds.
 */
class KoppeltaalActivationGuardTest {

  private static final String AUTO_CONFIGURATION = "nl.koppeltaal.hapi.autoconfigure.KoppeltaalAutoConfiguration";

  private final KoppeltaalActivationGuard guard = new KoppeltaalActivationGuard();

  @Test
  void letsAnActiveAutoConfigurationStart() {
    assertThatCode(() -> guard.postProcessEnvironment(new MockEnvironment(), new SpringApplication()))
      .doesNotThrowAnyException();
  }

  @Test
  void refusesAutoConfigurationSwitchedOff() {
    MockEnvironment environment = new MockEnvironment().withProperty("spring.boot.enableautoconfiguration", "false");

    assertThatThrownBy(() -> guard.postProcessEnvironment(environment, new SpringApplication()))
      .hasMessageContaining("spring.boot.enableautoconfiguration is false");
  }

  @Test
  void refusesAnExcludedKoppeltaalAutoConfiguration() {
    MockEnvironment environment = new MockEnvironment()
      .withProperty("spring.autoconfigure.exclude", "org.example.OtherAutoConfiguration, " + AUTO_CONFIGURATION);

    assertThatThrownBy(() -> guard.postProcessEnvironment(environment, new SpringApplication()))
      .hasMessageContaining("spring.autoconfigure.exclude names " + AUTO_CONFIGURATION);
  }

  @Test
  void refusesAnExclusionFromAnEnvironmentVariable() {
    MockEnvironment environment = new MockEnvironment();
    environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource("systemEnvironment",
      Map.of("SPRING_AUTOCONFIGURE_EXCLUDE", AUTO_CONFIGURATION)));

    assertThatThrownBy(() -> guard.postProcessEnvironment(environment, new SpringApplication()))
      .hasMessageContaining("spring.autoconfigure.exclude names " + AUTO_CONFIGURATION);
  }

  @Test
  void refusesWhenNoImportsFileNamesTheAutoConfiguration() {
    ClassLoader withoutImports = new ClassLoader(getClass().getClassLoader()) {
      @Override
      public Enumeration<URL> getResources(String name) throws IOException {
        return name.startsWith("META-INF/spring/") ? Collections.emptyEnumeration() : super.getResources(name);
      }
    };
    SpringApplication application = new SpringApplication();
    application.setResourceLoader(new DefaultResourceLoader(withoutImports));

    assertThatThrownBy(() -> guard.postProcessEnvironment(new MockEnvironment(), application))
      .hasMessageContaining("no AutoConfiguration.imports on the class path names " + AUTO_CONFIGURATION);
  }

  @Test
  void isRegisteredForEveryStartup() {
    // Boot's own post-processors need constructor arguments; only ours matters here, so the others are skipped.
    assertThat(SpringFactoriesLoader.forDefaultResourceLocation(getClass().getClassLoader())
      .load(EnvironmentPostProcessor.class, null, (factoryType, implementationName, failure) -> {
      })).anyMatch(KoppeltaalActivationGuard.class::isInstance);
  }
}
