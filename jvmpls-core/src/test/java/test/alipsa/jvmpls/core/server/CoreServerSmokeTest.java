package test.alipsa.jvmpls.core.server;

import org.junit.jupiter.api.Test;
import se.alipsa.jvmpls.core.server.CoreServer;   // from main module
import se.alipsa.jvmpls.core.model.Diagnostic;
import se.alipsa.jvmpls.core.model.Position;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

class CoreServerSmokeTest {

  @Test
  void openFile_printDiagnostics() throws Exception {
    Path dir = Files.createTempDirectory("jvm-pls-smoke");
    Path file = dir.resolve("Hello.java");
    String code = """
      public class Hello {
        void greet() { System.out.println("hi"); }
      }
      """;
    Files.writeString(file, code, StandardCharsets.UTF_8);
    String uri = file.toUri().toString();

    try (CoreServer server = CoreServer.createDefault(CoreServerSmokeTest::printDiagnostics)) {
      List<Diagnostic> diags = server.openFile(uri, code);
      printDiagnostics(uri, diags);
    }
  }

  private static void printDiagnostics(String uri, List<Diagnostic> diagnostics) {
    System.out.println("=== Diagnostics for " + uri + " ===");
    if (diagnostics == null || diagnostics.isEmpty()) {
      System.out.println("(none)");
      return;
    }
    for (Diagnostic d : diagnostics) {
      var r = d.getRange();
      System.out.printf("[%s] %s:%d:%d-%d:%d %s (source=%s, code=%s)%n",
          d.getSeverity(), uri, r.start.line, r.start.column, r.end.line, r.end.column,
          d.getMessage(), d.getSource(), d.getCode());
    }
  }
}
