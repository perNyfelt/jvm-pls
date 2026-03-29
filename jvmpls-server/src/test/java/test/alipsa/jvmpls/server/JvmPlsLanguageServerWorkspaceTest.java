package test.alipsa.jvmpls.server;

import io.github.classgraph.ClassGraph;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.DefinitionParams;
import org.eclipse.lsp4j.DidChangeWatchedFilesParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.FileChangeType;
import org.eclipse.lsp4j.FileEvent;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode;
import org.eclipse.lsp4j.services.LanguageClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import se.alipsa.jvmpls.server.JvmPlsLanguageServer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JvmPlsLanguageServerWorkspaceTest {

  private static final String WORKSPACE_MANAGER_LOGGER =
      "se.alipsa.jvmpls.server.WorkspaceManager";
  private static final String TEXT_DOCUMENT_SERVICE_LOGGER =
      "se.alipsa.jvmpls.server.JvmPlsTextDocumentService";
  private TestLogCapture workspaceLogs;
  private TestLogCapture textDocumentLogs;

  @BeforeEach
  void captureLogs() {
    workspaceLogs = TestLogCapture.capture(WORKSPACE_MANAGER_LOGGER);
    textDocumentLogs = TestLogCapture.capture(TEXT_DOCUMENT_SERVICE_LOGGER);
  }

  @AfterEach
  void restoreLogs() {
    if (textDocumentLogs != null) {
      textDocumentLogs.close();
      textDocumentLogs = null;
    }
    if (workspaceLogs != null) {
      workspaceLogs.close();
      workspaceLogs = null;
    }
  }

  @Test
  void completion_isRejectedBeforeInitialize() {
    JvmPlsLanguageServer server = new JvmPlsLanguageServer();

    CompletionException failure = assertThrows(
        CompletionException.class,
        () -> server.getTextDocumentService().completion(
            new CompletionParams(new TextDocumentIdentifier("file:///Test.java"), new Position(0, 0)))
            .join());

    assertTrue(failure.getCause() instanceof ResponseErrorException);
    ResponseErrorException error = (ResponseErrorException) failure.getCause();
    assertEquals(ResponseErrorCode.InvalidRequest.getValue(), error.getResponseError().getCode());
    assertTrue(textDocumentLogs.contains(Level.WARNING,
        "Rejecting textDocument/completion before initialization"));
  }

  @Test
  void initialize_resolvesMavenWorkspaceClasspath() throws Exception {
    Path root = createMavenWorkspaceWithDependency(true);
    Path javaFile = root.resolve("src/main/java/demo/Main.java");
    String code = """
        package demo;
        import io.github.classgraph.ClassGraph;
        public class Main {
          ClassGraph graph;
        }
        """;
    Files.writeString(javaFile, code, StandardCharsets.UTF_8);

    JvmPlsLanguageServer server = new JvmPlsLanguageServer();
    server.connect(new CapturingLanguageClient());
    initialize(server, root, null);

    server.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(
        new TextDocumentItem(javaFile.toUri().toString(), "java", 1, code)));

    Either<List<? extends org.eclipse.lsp4j.Location>, List<? extends org.eclipse.lsp4j.LocationLink>> result =
        server.getTextDocumentService().definition(
            new DefinitionParams(new TextDocumentIdentifier(javaFile.toUri().toString()),
                wordPosition(code, "ClassGraph")))
            .get(5, TimeUnit.SECONDS);

    assertTrue(result.isLeft());
    assertFalse(result.getLeft().isEmpty());
    assertTrue(result.getLeft().getFirst().getUri().contains("ClassGraph"));
  }

  @Test
  void initialize_usesManualClasspathOverrideWithoutWorkspaceRoot() throws Exception {
    Path classGraphJar = Path.of(ClassGraph.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    Path root = Files.createTempDirectory("jvmpls-manual-classpath");
    Path javaFile = root.resolve("Main.java");
    String code = """
        import io.github.classgraph.ClassGraph;
        class Main {
          ClassGraph graph;
        }
        """;
    Files.writeString(javaFile, code, StandardCharsets.UTF_8);

    JvmPlsLanguageServer server = new JvmPlsLanguageServer();
    server.connect(new CapturingLanguageClient());
    initialize(server, null, Map.of("classpath", List.of(classGraphJar.toString())));

    server.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(
        new TextDocumentItem(javaFile.toUri().toString(), "java", 1, code)));

    Either<List<? extends org.eclipse.lsp4j.Location>, List<? extends org.eclipse.lsp4j.LocationLink>> result =
        server.getTextDocumentService().definition(
            new DefinitionParams(new TextDocumentIdentifier(javaFile.toUri().toString()),
                wordPosition(code, "ClassGraph")))
            .get(5, TimeUnit.SECONDS);

    assertTrue(result.isLeft());
    assertFalse(result.getLeft().isEmpty());
  }

  @Test
  void didChangeWatchedFiles_refreshesResolvedClasspath() throws Exception {
    Path root = createMavenWorkspaceWithDependency(true);
    Path javaFile = root.resolve("src/main/java/demo/Main.java");
    String code = """
        package demo;
        import io.github.classgraph.ClassGraph;
        public class Main {
          ClassGraph graph;
        }
        """;
    Files.writeString(javaFile, code, StandardCharsets.UTF_8);

    JvmPlsLanguageServer server = new JvmPlsLanguageServer();
    server.connect(new CapturingLanguageClient());
    initialize(server, root, null);
    String uri = javaFile.toUri().toString();

    server.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(
        new TextDocumentItem(uri, "java", 1, code)));
    assertDefinitionPresent(server, uri, code);

    Files.writeString(root.resolve("pom.xml"), """
        <project xmlns="http://maven.apache.org/POM/4.0.0">
          <modelVersion>4.0.0</modelVersion>
          <groupId>demo</groupId>
          <artifactId>workspace</artifactId>
          <version>1.0.0</version>
        </project>
        """, StandardCharsets.UTF_8);

    server.getWorkspaceService().didChangeWatchedFiles(new DidChangeWatchedFilesParams(List.of(
        new FileEvent(root.resolve("pom.xml").toUri().toString(), FileChangeType.Changed))));

    Either<List<? extends org.eclipse.lsp4j.Location>, List<? extends org.eclipse.lsp4j.LocationLink>> result =
        server.getTextDocumentService().definition(
            new DefinitionParams(new TextDocumentIdentifier(uri), wordPosition(code, "ClassGraph")))
            .get(5, TimeUnit.SECONDS);

    assertTrue(result.isLeft());
    assertTrue(result.getLeft().isEmpty(), "definition should disappear after dependency refresh");
  }

  @Test
  void initialize_warnsAndFallsBackWhenBuildResolutionFails() throws Exception {
    Path root = Files.createTempDirectory("jvmpls-broken-workspace");
    Path javaFile = root.resolve("src/main/java/demo/Main.java");
    Files.createDirectories(javaFile.getParent());
    Files.writeString(root.resolve("pom.xml"), "<project><broken>", StandardCharsets.UTF_8);
    String code = """
        package demo;
        public class Main {
          String value;
        }
        """;
    Files.writeString(javaFile, code, StandardCharsets.UTF_8);

    JvmPlsLanguageServer server = new JvmPlsLanguageServer();
    CapturingLanguageClient client = new CapturingLanguageClient();
    server.connect(client);
    initialize(server, root, null);

    assertTrue(client.messages.stream().anyMatch(message ->
            message.getMessage().contains("falling back to JDK-only symbols")),
        "client should be warned when workspace resolution falls back");
    assertTrue(workspaceLogs.contains(Level.WARNING, "Workspace resolution failed after initialize"));

    server.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(
        new TextDocumentItem(javaFile.toUri().toString(), "java", 1, code)));

    Either<List<? extends org.eclipse.lsp4j.Location>, List<? extends org.eclipse.lsp4j.LocationLink>> result =
        server.getTextDocumentService().definition(
            new DefinitionParams(new TextDocumentIdentifier(javaFile.toUri().toString()),
                wordPosition(code, "String")))
            .get(5, TimeUnit.SECONDS);

    assertTrue(result.isLeft(), "fallback initialization should keep requests functional");
  }

  @Test
  void initialize_warnsWhenWorkspaceUriIsUnsupported() throws Exception {
    JvmPlsLanguageServer server = new JvmPlsLanguageServer();
    CapturingLanguageClient client = new CapturingLanguageClient();
    server.connect(client);

    InitializeParams params = new InitializeParams();
    params.setRootUri("vscode-remote://ssh-remote+demo/workspace");
    server.initialize(params).get(5, TimeUnit.SECONDS);

    assertTrue(client.messages.stream().anyMatch(message ->
            message.getMessage().contains("Ignoring unsupported workspace URI")),
        "client should be warned about unsupported non-file workspace URIs");
    assertTrue(workspaceLogs.contains(Level.WARNING, "Ignoring unsupported workspace URI"));
  }

  private static void assertDefinitionPresent(JvmPlsLanguageServer server, String uri, String code) throws Exception {
    Either<List<? extends org.eclipse.lsp4j.Location>, List<? extends org.eclipse.lsp4j.LocationLink>> result =
        server.getTextDocumentService().definition(
            new DefinitionParams(new TextDocumentIdentifier(uri), wordPosition(code, "ClassGraph")))
            .get(5, TimeUnit.SECONDS);
    assertTrue(result.isLeft());
    assertFalse(result.getLeft().isEmpty());
  }

  private static void initialize(JvmPlsLanguageServer server, Path root, Object initializationOptions)
      throws Exception {
    InitializeParams params = new InitializeParams();
    params.setRootUri(root == null ? null : root.toUri().toString());
    params.setInitializationOptions(initializationOptions);
    server.initialize(params).get(5, TimeUnit.SECONDS);
  }

  private static Position wordPosition(String code, String word) {
    int idx = code.indexOf(word);
    int line = 0;
    int column = 0;
    for (int i = 0; i < idx; i++) {
      if (code.charAt(i) == '\n') {
        line++;
        column = 0;
      } else {
        column++;
      }
    }
    return new Position(line, column);
  }

  private static Path createMavenWorkspaceWithDependency(boolean includeDependency) throws Exception {
    Path root = Files.createTempDirectory("jvmpls-maven-workspace");
    Files.createDirectories(root.resolve("src/main/java/demo"));
    String dependencySection = includeDependency ? """
          <dependencies>
            <dependency>
              <groupId>io.github.classgraph</groupId>
              <artifactId>classgraph</artifactId>
              <version>4.8.184</version>
            </dependency>
          </dependencies>
        """ : "";
    Files.writeString(root.resolve("pom.xml"), """
        <project xmlns="http://maven.apache.org/POM/4.0.0">
          <modelVersion>4.0.0</modelVersion>
          <groupId>demo</groupId>
          <artifactId>workspace</artifactId>
          <version>1.0.0</version>
        %s
        </project>
        """.formatted(dependencySection), StandardCharsets.UTF_8);
    return root;
  }

  private static final class CapturingLanguageClient implements LanguageClient {

    private final List<org.eclipse.lsp4j.MessageParams> messages = new ArrayList<>();

    @Override
    public void telemetryEvent(Object object) {
    }

    @Override
    public void publishDiagnostics(PublishDiagnosticsParams diagnostics) {
    }

    @Override
    public void showMessage(org.eclipse.lsp4j.MessageParams messageParams) {
      messages.add(messageParams);
    }

    @Override
    public CompletableFuture<org.eclipse.lsp4j.MessageActionItem> showMessageRequest(
        org.eclipse.lsp4j.ShowMessageRequestParams requestParams) {
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public void logMessage(org.eclipse.lsp4j.MessageParams message) {
    }
  }
}
