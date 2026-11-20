package nl.koppeltaal.hapi.autoconfigure;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Stops the startup when the extension jar is present but KoppeltaalAutoConfiguration cannot run: auto-configuration
 * switched off, the class excluded, or no AutoConfiguration.imports naming it. META-INF/spring.factories registers it,
 * so it runs before the application context exists, no property switches it off, and it does not depend on
 * hapi.fhir.custom-interceptor-classes. Without the jar, the required import in docker/config stops the startup instead.
 */
public class KoppeltaalActivationGuard implements EnvironmentPostProcessor, Ordered {

  private static final String AUTO_CONFIGURATION = KoppeltaalAutoConfiguration.class.getName();

  @Override
  public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
    List<String> problems = new ArrayList<>();
    if (!environment.getProperty(EnableAutoConfiguration.ENABLED_OVERRIDE_PROPERTY, Boolean.class, true)) {
      problems.add(EnableAutoConfiguration.ENABLED_OVERRIDE_PROPERTY + " is false");
    }
    List<String> excluded = Binder.get(environment)
      .bind("spring.autoconfigure.exclude", Bindable.listOf(String.class)).orElse(List.of());
    if (excluded.stream().map(String::trim).anyMatch(AUTO_CONFIGURATION::equals)) {
      problems.add("spring.autoconfigure.exclude names " + AUTO_CONFIGURATION);
    }
    List<String> candidates = new ArrayList<>();
    ImportCandidates.load(AutoConfiguration.class, application.getClassLoader()).forEach(candidates::add);
    if (!candidates.contains(AUTO_CONFIGURATION)) {
      problems.add("no AutoConfiguration.imports on the class path names " + AUTO_CONFIGURATION);
    }
    if (!problems.isEmpty()) {
      throw new IllegalStateException("The Koppeltaal extension cannot activate: " + String.join("; ", problems));
    }
  }

  /** After the config data post-processor, so that exclusions from configuration files count too. */
  @Override
  public int getOrder() {
    return Ordered.LOWEST_PRECEDENCE;
  }
}
