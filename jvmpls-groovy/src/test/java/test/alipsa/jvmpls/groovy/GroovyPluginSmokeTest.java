package test.alipsa.jvmpls.groovy;

import org.junit.jupiter.api.Test;
import se.alipsa.jvmpls.core.model.Diagnostic;
import se.alipsa.jvmpls.core.server.CoreServer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class GroovyPluginSmokeTest {

  @Test
  void openFile_indexesWithoutDiagnostics() throws Exception {
    Path dir = Files.createTempDirectory("jvmpls-groovy-smoke");
    Path file = dir.resolve("Hello.groovy");
    String code = """
      package demo
      class Hello { def greet() { println 'hi' } }
      """;
    Files.writeString(file, code, StandardCharsets.UTF_8);
    String uri = file.toUri().toString();

    try (CoreServer server = CoreServer.createDefault((u, d) -> {})) {
      List<Diagnostic> diags = server.openFile(uri, code);
      assertTrue(diags.isEmpty(), "Expected no diagnostics from groovy plugin");
    }
  }
}
