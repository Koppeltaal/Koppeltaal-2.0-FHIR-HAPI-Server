package nl.koppeltaal.hapi.autoconfigure;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.source.ConfigurationProperty;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.context.properties.source.IterableConfigurationPropertySource;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Stops the startup when any property source sets a starter key for a feature Koppeltaal replaces: a configuration file,
 * an environment variable, SPRING_APPLICATION_JSON, a system property or a command-line argument, whatever the value. On
 * the fork these keys switched the Koppeltaal features themselves, so a leftover would now be ignored and change
 * behaviour silently. Keys match as Spring Boot's relaxed binding matches them, which covers at least every spelling the
 * starter read. It runs after Spring Boot has loaded the configuration files; META-INF/spring.factories registers it.
 */
public class ReplacedStarterSwitchGuard implements EnvironmentPostProcessor, Ordered {

  private static final Map<ConfigurationPropertyName, String> REPLACED = new LinkedHashMap<>();

  static {
    REPLACED.put(ConfigurationPropertyName.of("hapi.fhir.allow-cascading-deletes"), "koppeltaal.cascading-deletes.enabled");
    REPLACED.put(ConfigurationPropertyName.of("hapi.fhir.enable-repository-validating-interceptor"),
      "koppeltaal.repository-validation.enabled");
    REPLACED.put(ConfigurationPropertyName.of("hapi.fhir.cors"), "koppeltaal.cors, with koppeltaal.cors.enabled: true");
    REPLACED.put(ConfigurationPropertyName.of("hapi.fhir.implementationguides"),
      "koppeltaal.implementationguides, with the same keys per guide");
    // HAPI_FHIR_IMPLEMENTATION_GUIDES_* reads as this name; on the fork it overrode a guide declared as implementation-guides
    REPLACED.put(ConfigurationPropertyName.of("hapi.fhir.implementation.guides"),
      "koppeltaal.implementationguides, with the same keys per guide");
  }

  @Override
  public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
    List<String> problems = new ArrayList<>();
    REPLACED.forEach((starterKey, koppeltaalKey) -> {
      List<String> settings = settingsOf(environment, starterKey);
      if (!settings.isEmpty()) {
        problems.add(starterKey + " is set (" + String.join(", ", settings) + "); Koppeltaal replaces it with "
          + koppeltaalKey);
      }
    });
    if (!problems.isEmpty()) {
      throw new IllegalStateException("Replaced starter settings found: " + String.join("; ", problems));
    }
  }

  /** The key itself and every key below it, each with where it comes from. */
  private static List<String> settingsOf(ConfigurableEnvironment environment, ConfigurationPropertyName key) {
    List<String> settings = new ArrayList<>();
    for (ConfigurationPropertySource source : ConfigurationPropertySources.get(environment)) {
      ConfigurationProperty property = source.getConfigurationProperty(key);
      if (property != null) {
        settings.add(describe(property));
      }
      if (source instanceof IterableConfigurationPropertySource iterable) {
        iterable.stream().filter(key::isAncestorOf).map(iterable::getConfigurationProperty).filter(Objects::nonNull)
          .map(ReplacedStarterSwitchGuard::describe).forEach(settings::add);
      }
    }
    return settings;
  }

  private static String describe(ConfigurationProperty property) {
    return property.getOrigin() == null ? property.getName().toString()
      : property.getName() + " from " + property.getOrigin();
  }

  @Override
  public int getOrder() {
    return Ordered.LOWEST_PRECEDENCE;
  }
}
