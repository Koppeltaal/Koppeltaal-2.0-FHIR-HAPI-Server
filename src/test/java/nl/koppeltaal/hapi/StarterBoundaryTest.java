package nl.koppeltaal.hapi;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** The extension builds on the starter only through its documented hooks: main code imports none of its classes. */
class StarterBoundaryTest {

  @Test
  void mainCodeImportsNothingFromTheStarter() throws IOException {
    try (Stream<Path> sources = Files.walk(Path.of("src/main/java"))) {
      List<String> offenders = sources.filter(source -> source.toString().endsWith(".java"))
        .filter(StarterBoundaryTest::importsTheStarter).map(Path::toString).toList();
      assertThat(offenders).isEmpty();
    }
  }

  private static boolean importsTheStarter(Path source) {
    try {
      return Files.readString(source).contains("import ca.uhn.fhir.jpa.starter.");
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }
}
