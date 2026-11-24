package nl.koppeltaal.hapi.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import ca.uhn.fhir.batch2.config.Batch2JobRegisterer;
import ca.uhn.fhir.jpa.packages.PackageInstallationSpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import nl.koppeltaal.hapi.service.PackageInstallerWithExclusionSvcImpl;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.mock.env.MockEnvironment;

/**
 * The fork installed every entry of hapi.fhir.implementationguides, in configuration order, each without example
 * instances. koppeltaal.implementationguides takes the same keys.
 */
class KoppeltaalPackageInstallerTest {

  static final Path CONFIG = Path.of("docker/config/application.yaml");

  private static final List<String> INSTALL_TYPES = List.of("StructureDefinition", "ValueSet", "CodeSystem",
    "SearchParameter", "CapabilityStatement", "OperationDefinition", "ConceptMap", "NamingSystem", "ImplementationGuide");

  private final PackageInstallerWithExclusionSvcImpl installer = mock(PackageInstallerWithExclusionSvcImpl.class);
  private final Batch2JobRegisterer batch2JobRegisterer = mock(Batch2JobRegisterer.class);

  @Test
  void installsTheShippedGuideLikeTheStarterInstalledItsOwn() throws Exception {
    install(shippedConfiguration());

    PackageInstallationSpec guide = installed(1).get(0);
    assertThat(guide.getName()).isEqualTo("koppeltaalv2.00");
    assertThat(guide.isFetchDependencies()).isTrue();
    assertThat(guide.getInstallMode()).isEqualTo(PackageInstallationSpec.InstallModeEnum.STORE_AND_INSTALL);
  }

  @Test
  void installsEveryGuideAfterStartingBatch2InConfigurationOrder() throws Exception {
    install(configuration("""
      koppeltaal:
        implementationguides:
          other:
            name: example.fhir.other
            version: 1.0.0
          kt2:
            name: koppeltaalv2.00
            version: 0.16.6
      """));

    InOrder order = inOrder(batch2JobRegisterer, installer);
    order.verify(batch2JobRegisterer).start();
    order.verify(installer, times(2)).install(any());
    List<PackageInstallationSpec> guides = installed(2);
    assertThat(guides).extracting(PackageInstallationSpec::getName).containsExactly("example.fhir.other", "koppeltaalv2.00");
    guides.forEach(guide -> {
      assertThat(guide.getDependencyExcludes())
        .contains("hl7.fhir.r2.core", "hl7.fhir.r3.core", "hl7.fhir.r4.core", "hl7.fhir.r5.core");
      assertThat(guide.getInstallResourceTypes()).containsExactlyElementsOf(INSTALL_TYPES);
    });
  }

  @Test
  void keepsTheCoreDependenciesWhenTransitiveInstallationIsOff() throws Exception {
    install(shippedConfiguration().withProperty("hapi.fhir.install_transitive_ig_dependencies", "false"));

    assertThat(installed(1).get(0).getDependencyExcludes()).isEmpty();
  }

  /** The starter bound the flag with relaxed binding, so the kebab-case spelling switched it off too. */
  @Test
  void readsTheTransitiveFlagInEverySpellingTheStarterRead() throws Exception {
    install(shippedConfiguration().withProperty("hapi.fhir.install-transitive-ig-dependencies", "false"));

    assertThat(installed(1).get(0).getDependencyExcludes()).isEmpty();
  }

  @Test
  void installsNothingWithoutGuidesButStillStartsBatch2() {
    install(new MockEnvironment());

    verify(batch2JobRegisterer).start();
    verify(installer, never()).install(any());
  }

  /** HAPI's installer needs both, so such a guide must stop the startup instead of being skipped. */
  @Test
  void refusesAGuideWithoutNameOrVersion() {
    MockEnvironment environment = new MockEnvironment().withProperty("koppeltaal.implementationguides.kt2.version", "0.16.6");

    assertThatThrownBy(() -> install(environment))
      .isInstanceOf(IllegalStateException.class)
      .hasMessageContaining("koppeltaal.implementationguides.kt2 needs a name and a version");
    verify(installer, never()).install(any());
  }

  /** K8S secrets arrive as environment variables. */
  @Test
  void refusesAGuideFromAnEnvironmentVariableWithoutAName() {
    MockEnvironment environment = new MockEnvironment();
    environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource("systemEnvironment",
      Map.of("KOPPELTAAL_IMPLEMENTATIONGUIDES_KT2_VERSION", "0.16.6")));

    assertThatThrownBy(() -> install(environment)).hasMessageContaining("koppeltaal.implementationguides.kt2");
  }

  private void install(org.springframework.core.env.Environment environment) {
    new StarterReplacementConfig().koppeltaalPackageInstaller(installer, batch2JobRegisterer, environment);
  }

  private List<PackageInstallationSpec> installed(int count) {
    ArgumentCaptor<PackageInstallationSpec> captor = ArgumentCaptor.forClass(PackageInstallationSpec.class);
    verify(installer, times(count)).install(captor.capture());
    return captor.getAllValues();
  }

  static MockEnvironment shippedConfiguration() throws Exception {
    return configuration(new FileSystemResource(CONFIG));
  }

  /** A configuration file keeps its order; MockEnvironment.withProperty does not. */
  private static MockEnvironment configuration(String yaml) throws Exception {
    return configuration(new ByteArrayResource(yaml.getBytes(StandardCharsets.UTF_8)));
  }

  private static MockEnvironment configuration(Resource yaml) throws Exception {
    MockEnvironment environment = new MockEnvironment();
    new YamlPropertySourceLoader().load("config", yaml).forEach(environment.getPropertySources()::addLast);
    return environment;
  }
}
