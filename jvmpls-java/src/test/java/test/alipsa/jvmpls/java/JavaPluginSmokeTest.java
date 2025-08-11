package test.alipsa.jvmpls.java;

import org.junit.jupiter.api.Test;
import se.alipsa.jvmpls.core.model.Diagnostic;
import se.alipsa.jvmpls.core.server.CoreServer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JavaPluginSmokeTest {

  @Test
  void openFile_indexesWithoutDiagnostics() throws Exception {
    Path dir = Files.createTempDirectory("jvmpls-java-smoke");
    Path file = dir.resolve("Hello.java");
    String code = """
      package demo;
      public class Hello {
        void greet() { System.out.println("hi"); }
      }
      """;
    Files.writeString(file, code, StandardCharsets.UTF_8);
    String uri = file.toUri().toString();

    try (CoreServer server = CoreServer.createDefault(JavaPluginSmokeTest::printDiags)) {
      List<Diagnostic> diags = server.openFile(uri, code);
      // With a valid Java file, our plugin returns no diagnostics
      assertTrue(diags.isEmpty(), "Expected no diagnostics from java plugin");
    }
  }

  private static void printDiags(String uri, List<Diagnostic> diags) {
    System.out.println("=== Diagnostics for " + uri + " ===");
    if (diags.isEmpty()) System.out.println("(none)");
    else diags.forEach(d -> System.out.println(d.getSeverity() + " " + d.getMessage()));
  }
}
