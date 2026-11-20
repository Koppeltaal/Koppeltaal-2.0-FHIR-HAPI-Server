package nl.koppeltaal.hapi.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import ca.uhn.fhir.batch2.config.Batch2JobRegisterer;
import ca.uhn.fhir.jpa.packages.PackageInstallationSpec;
import java.nio.file.Path;
import nl.koppeltaal.hapi.service.PackageInstallerWithExclusionSvcImpl;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;

class KoppeltaalPackageInstallerTest {

  static final Path CONFIG = Path.of("src/main/resources/application.yaml");

  private final PackageInstallerWithExclusionSvcImpl installer = mock(PackageInstallerWithExclusionSvcImpl.class);
  private final Batch2JobRegisterer batch2JobRegisterer = mock(Batch2JobRegisterer.class);

  @Test
  void bindsTheConfiguredGuideLikeTheStarterBoundItsOwn() throws Exception {
    PackageInstallationSpec guide = configuredGuide();

    assertThat(guide.getName()).isEqualTo("koppeltaalv2.00");
    assertThat(guide.isFetchDependencies()).isTrue();
    assertThat(guide.getInstallMode()).isEqualTo(PackageInstallationSpec.InstallModeEnum.STORE_AND_INSTALL);
  }

  @Test
  void installsTheConfiguredGuideAfterStartingBatch2() throws Exception {
    new StarterReplacementConfig().koppeltaalPackageInstaller(installer, batch2JobRegisterer, provider(configuredGuide()), true);

    InOrder order = inOrder(batch2JobRegisterer, installer);
    order.verify(batch2JobRegisterer).start();
    ArgumentCaptor<PackageInstallationSpec> installed = ArgumentCaptor.forClass(PackageInstallationSpec.class);
    order.verify(installer).install(installed.capture());
    assertThat(installed.getValue().isFetchDependencies()).isTrue();
    assertThat(installed.getValue().getDependencyExcludes())
      .contains("hl7.fhir.r2.core", "hl7.fhir.r3.core", "hl7.fhir.r4.core", "hl7.fhir.r5.core");
    assertThat(installed.getValue().getInstallResourceTypes()).containsExactly("StructureDefinition", "ValueSet",
      "CodeSystem", "SearchParameter", "CapabilityStatement", "OperationDefinition", "ConceptMap", "NamingSystem",
      "ImplementationGuide");
  }

  @Test
  void keepsTheCoreDependenciesWhenTransitiveInstallationIsOff() throws Exception {
    new StarterReplacementConfig().koppeltaalPackageInstaller(installer, batch2JobRegisterer, provider(configuredGuide()), false);

    ArgumentCaptor<PackageInstallationSpec> installed = ArgumentCaptor.forClass(PackageInstallationSpec.class);
    verify(installer).install(installed.capture());
    assertThat(installed.getValue().getDependencyExcludes()).isEmpty();
  }

  @Test
  void installsNothingWithoutAGuideButStillStartsBatch2() {
    new StarterReplacementConfig().koppeltaalPackageInstaller(installer, batch2JobRegisterer, provider(null), true);

    verify(batch2JobRegisterer).start();
    verify(installer, never()).install(any());
  }

  static PackageInstallationSpec configuredGuide() throws Exception {
    StandardEnvironment environment = new StandardEnvironment();
    new YamlPropertySourceLoader().load("config", new FileSystemResource(CONFIG))
      .forEach(environment.getPropertySources()::addLast);
    return Binder.get(environment).bind("koppeltaal.implementationguide", PackageInstallationSpec.class).get();
  }

  private static ObjectProvider<PackageInstallationSpec> provider(PackageInstallationSpec guide) {
    StaticListableBeanFactory beans = new StaticListableBeanFactory();
    if (guide != null) {
      beans.addBean("koppeltaalImplementationGuide", guide);
    }
    return beans.getBeanProvider(PackageInstallationSpec.class);
  }
}
