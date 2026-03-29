package test.alipsa.jvmpls.core.server;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import se.alipsa.jvmpls.core.model.Diagnostic;
import se.alipsa.jvmpls.core.server.CoreServer;

class CoreServerSmokeTest {

  @Test
  void openFile_printDiagnostics() throws Exception {
    Path dir = Files.createTempDirectory("jvm-pls-smoke");
    Path file = dir.resolve("Hello.java");
    String code =
        """
        public class Hello {
          void greet() { System.out.println("hi"); }
        }
        """;
    Files.writeString(file, code, StandardCharsets.UTF_8);
    String uri = file.toUri().toString();

    try (CoreServer server = CoreServer.createDefault((ignoredUri, diagnostics) -> {})) {
      List<Diagnostic> diags = server.openFile(uri, code);
      org.junit.jupiter.api.Assertions.assertTrue(diags.isEmpty());
    }
  }
}
