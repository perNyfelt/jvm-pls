package test.alipsa.jvmpls.bench;

import java.util.List;

import org.junit.jupiter.api.Test;

import se.alipsa.jvmpls.core.model.CompletionItem;
import se.alipsa.jvmpls.core.model.Diagnostic;
import se.alipsa.jvmpls.core.model.Position;
import se.alipsa.jvmpls.core.server.CoreServer;

/**
 * Performance benchmarks for jvm-pls indexing, completion, and definition latency. Run with: {@code
 * mvn -pl jvmpls-bench -Pbench test}
 *
 * <p>Results are printed to stdout with timing information.
 */
class IndexingBench {

  @Test
  void coldStartIndexing100Files() {
    runColdStart(100);
  }

  @Test
  void coldStartIndexing1000Files() {
    runColdStart(1000);
  }

  @Test
  void singleFileBodyOnlyEdit() {
    List<SyntheticWorkspace.SourceFile> files = SyntheticWorkspace.generate(100);
    CoreServer server = CoreServer.createDefault(noopPublisher());

    // Cold-start index all files
    for (SyntheticWorkspace.SourceFile f : files) {
      server.openFile(f.uri(), f.content());
    }

    // Pick a Java file and make a body-only edit
    SyntheticWorkspace.SourceFile target =
        files.stream().filter(f -> f.language().equals("java")).findFirst().orElseThrow();
    String edited = target.content().replace("// body", "// edited body content");

    // Warm up
    server.changeFile(target.uri(), edited);
    server.changeFile(target.uri(), target.content());

    // Measure
    long start = System.nanoTime();
    int iterations = 50;
    for (int i = 0; i < iterations; i++) {
      server.changeFile(target.uri(), edited);
      server.changeFile(target.uri(), target.content());
    }
    long elapsed = System.nanoTime() - start;
    double avgMs = (elapsed / 1_000_000.0) / (iterations * 2);
    System.out.println("[BENCH] Body-only edit avg: " + String.format("%.2f", avgMs) + " ms");

    server.close();
  }

  @Test
  void singleFileApiChangeEdit() {
    List<SyntheticWorkspace.SourceFile> files = SyntheticWorkspace.generate(100);
    CoreServer server = CoreServer.createDefault(noopPublisher());

    for (SyntheticWorkspace.SourceFile f : files) {
      server.openFile(f.uri(), f.content());
    }

    // Pick a Java file and add a new public method (API change)
    SyntheticWorkspace.SourceFile target =
        files.stream().filter(f -> f.language().equals("java")).findFirst().orElseThrow();
    String edited = target.content().replace("}\n", "  public void newApiMethod() {}\n}\n");

    long start = System.nanoTime();
    int iterations = 20;
    for (int i = 0; i < iterations; i++) {
      server.changeFile(target.uri(), edited);
      server.changeFile(target.uri(), target.content());
    }
    long elapsed = System.nanoTime() - start;
    double avgMs = (elapsed / 1_000_000.0) / (iterations * 2);
    System.out.println("[BENCH] API-change edit avg: " + String.format("%.2f", avgMs) + " ms");

    server.close();
  }

  @Test
  void completionLatency() {
    List<SyntheticWorkspace.SourceFile> files = SyntheticWorkspace.generate(100);
    CoreServer server = CoreServer.createDefault(noopPublisher());

    for (SyntheticWorkspace.SourceFile f : files) {
      server.openFile(f.uri(), f.content());
    }

    SyntheticWorkspace.SourceFile target =
        files.stream().filter(f -> f.language().equals("java")).findFirst().orElseThrow();
    Position pos = new Position(5, 0); // somewhere in the file

    // Warm up
    server.completions(target.uri(), pos);

    long start = System.nanoTime();
    int iterations = 100;
    for (int i = 0; i < iterations; i++) {
      List<CompletionItem> items = server.completions(target.uri(), pos);
    }
    long elapsed = System.nanoTime() - start;
    double avgMs = (elapsed / 1_000_000.0) / iterations;
    System.out.println("[BENCH] Completion avg: " + String.format("%.2f", avgMs) + " ms");

    server.close();
  }

  @Test
  void definitionLatency() {
    List<SyntheticWorkspace.SourceFile> files = SyntheticWorkspace.generate(100);
    CoreServer server = CoreServer.createDefault(noopPublisher());

    for (SyntheticWorkspace.SourceFile f : files) {
      server.openFile(f.uri(), f.content());
    }

    SyntheticWorkspace.SourceFile target =
        files.stream().filter(f -> f.language().equals("java")).findFirst().orElseThrow();
    Position pos = new Position(5, 0);

    // Warm up
    server.definition(target.uri(), pos);

    long start = System.nanoTime();
    int iterations = 100;
    for (int i = 0; i < iterations; i++) {
      server.definition(target.uri(), pos);
    }
    long elapsed = System.nanoTime() - start;
    double avgMs = (elapsed / 1_000_000.0) / iterations;
    System.out.println("[BENCH] Definition avg: " + String.format("%.2f", avgMs) + " ms");

    server.close();
  }

  private void runColdStart(int fileCount) {
    List<SyntheticWorkspace.SourceFile> files = SyntheticWorkspace.generate(fileCount);

    long start = System.nanoTime();
    CoreServer server = CoreServer.createDefault(noopPublisher());
    for (SyntheticWorkspace.SourceFile f : files) {
      List<Diagnostic> diags = server.openFile(f.uri(), f.content());
    }
    long elapsed = System.nanoTime() - start;
    double ms = elapsed / 1_000_000.0;
    System.out.println(
        "[BENCH] Cold start " + fileCount + " files: " + String.format("%.0f", ms) + " ms");
    server.close();
  }

  private static se.alipsa.jvmpls.core.server.DiagnosticsPublisher noopPublisher() {
    return (uri, diags) -> {};
  }
}
