package test.alipsa.jvmpls.server;

import static org.junit.jupiter.api.Assertions.*;

import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.*;

import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.jsonrpc.messages.Either3;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import se.alipsa.jvmpls.server.JvmPlsLanguageServer;

/**
 * Integration tests that exercise the full LSP lifecycle over JSON-RPC using piped streams to
 * connect client and server in-process.
 */
class JvmPlsLanguageServerTest {

  private static final int TIMEOUT_SECONDS = 30;
  private static final String WORKSPACE_MANAGER_LOGGER = "se.alipsa.jvmpls.server.WorkspaceManager";
  private static final String REMOTE_ENDPOINT_LOGGER = "org.eclipse.lsp4j.jsonrpc.RemoteEndpoint";

  private JvmPlsLanguageServer server;
  private LanguageServer serverProxy;
  private TestLanguageClient testClient;
  private Future<Void> serverListening;
  private Future<Void> clientListening;
  private PipedOutputStream clientOut;
  private PipedOutputStream serverOut;
  private PipedInputStream clientIn;
  private PipedInputStream serverIn;
  private TestLogCapture workspaceLogs;
  private TestLogCapture remoteEndpointLogs;

  @BeforeEach
  void setUp() throws Exception {
    // Two piped stream pairs:
    // client writes to clientOut -> server reads from serverIn
    // server writes to serverOut -> client reads from clientIn
    clientOut = new PipedOutputStream();
    serverIn = new PipedInputStream(clientOut);

    serverOut = new PipedOutputStream();
    clientIn = new PipedInputStream(serverOut);

    server = new JvmPlsLanguageServer();
    testClient = new TestLanguageClient();
    workspaceLogs = TestLogCapture.capture(WORKSPACE_MANAGER_LOGGER);
    remoteEndpointLogs = TestLogCapture.capture(REMOTE_ENDPOINT_LOGGER);

    // Create server-side launcher: server reads from serverIn, writes to serverOut
    Launcher<LanguageClient> serverLauncher =
        LSPLauncher.createServerLauncher(server, serverIn, serverOut);
    server.connect(serverLauncher.getRemoteProxy());

    // Create client-side launcher: client reads from clientIn, writes to clientOut
    Launcher<LanguageServer> clientLauncher =
        LSPLauncher.createClientLauncher(testClient, clientIn, clientOut);
    serverProxy = clientLauncher.getRemoteProxy();

    // Start listening on daemon threads
    serverListening = serverLauncher.startListening();
    clientListening = clientLauncher.startListening();
  }

  @AfterEach
  void tearDown() throws Exception {
    // Shutdown the server gracefully
    if (serverProxy != null) {
      try {
        serverProxy.shutdown().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      } catch (Exception ignored) {
        // best effort
      }
    }

    // Close all streams to unblock listening threads
    closeQuietly(clientOut);
    closeQuietly(serverOut);
    closeQuietly(clientIn);
    closeQuietly(serverIn);

    // Cancel listening futures
    if (serverListening != null) serverListening.cancel(true);
    if (clientListening != null) clientListening.cancel(true);
    if (workspaceLogs != null) {
      workspaceLogs.close();
      workspaceLogs = null;
    }
    if (remoteEndpointLogs != null) {
      remoteEndpointLogs.close();
      remoteEndpointLogs = null;
    }
  }

  // -------------------------------------------------------------------------
  // Test 1: initialize returns capabilities
  // -------------------------------------------------------------------------

  @Test
  void initialize_returnsCapabilities() throws Exception {
    InitializeResult result = initialize();

    assertNotNull(result.getCapabilities(), "capabilities should not be null");

    // Text document sync should advertise open/close and full change sync
    Either<TextDocumentSyncKind, TextDocumentSyncOptions> syncCapability =
        result.getCapabilities().getTextDocumentSync();
    assertNotNull(syncCapability, "textDocumentSync should not be null");
    assertTrue(syncCapability.isRight(), "textDocumentSync should be TextDocumentSyncOptions");
    TextDocumentSyncOptions syncOptions = syncCapability.getRight();
    assertTrue(syncOptions.getOpenClose(), "openClose should be true");
    assertEquals(TextDocumentSyncKind.Incremental, syncOptions.getChange());

    CompletionOptions completionProvider = result.getCapabilities().getCompletionProvider();
    assertNotNull(completionProvider, "completionProvider should not be null");
    assertTrue(
        completionProvider.getTriggerCharacters().contains("."),
        "trigger characters should include '.'");
    assertNotNull(result.getCapabilities().getHoverProvider());
    assertNotNull(result.getCapabilities().getReferencesProvider());
    assertNotNull(result.getCapabilities().getDocumentSymbolProvider());
    assertNotNull(result.getCapabilities().getWorkspaceSymbolProvider());
    assertNotNull(result.getCapabilities().getSignatureHelpProvider());
    assertNotNull(result.getCapabilities().getCodeActionProvider());
    assertNotNull(result.getCapabilities().getTypeDefinitionProvider());
    assertNotNull(result.getCapabilities().getImplementationProvider());
    assertNotNull(result.getCapabilities().getDocumentFormattingProvider());
    assertNotNull(result.getCapabilities().getRenameProvider());
    assertNotNull(result.getCapabilities().getCallHierarchyProvider());

    assertNotNull(result.getServerInfo(), "serverInfo should not be null");
    assertEquals("jvm-pls", result.getServerInfo().getName());
  }

  // -------------------------------------------------------------------------
  // Test 2: didOpen publishes diagnostics
  // -------------------------------------------------------------------------

  @Test
  void didOpen_publishesDiagnostics() throws Exception {
    initialize();

    Path dir = Files.createTempDirectory("jvm-pls-lsp-test");
    Path file = dir.resolve("Hello.java");
    String code =
        """
        public class Hello {
          void greet() { System.out.println("hi"); }
        }
        """;
    Files.writeString(file, code, StandardCharsets.UTF_8);
    String uri = file.toUri().toString();

    serverProxy
        .getTextDocumentService()
        .didOpen(new DidOpenTextDocumentParams(new TextDocumentItem(uri, "java", 1, code)));

    // Wait for diagnostics to arrive (async via piped streams)
    PublishDiagnosticsParams diagnostics = testClient.awaitDiagnostics(uri, TIMEOUT_SECONDS);
    assertNotNull(diagnostics, "should have received publishDiagnostics for " + uri);
    assertEquals(uri, diagnostics.getUri());
  }

  // -------------------------------------------------------------------------
  // Test 3: completion returns results
  // -------------------------------------------------------------------------

  @Test
  void completion_returnsResults() throws Exception {
    initialize();

    Path dir = Files.createTempDirectory("jvm-pls-lsp-completion");
    Path pkgDir = Files.createDirectories(dir.resolve("com/example"));

    // Foo.java defines class Foo in package com.example
    Path fooFile = pkgDir.resolve("Foo.java");
    String fooCode =
        "package com.example;\n\npublic class Foo {\n  public void doSomething() {}\n}\n";
    Files.writeString(fooFile, fooCode, StandardCharsets.UTF_8);
    String fooUri = fooFile.toUri().toString();

    // Bar.java references "Fo" - cursor at end of "Fo" in package com.example
    Path barFile = pkgDir.resolve("Bar.java");
    String barCode =
        "package com.example;\n\npublic class Bar {\n  void test() {\n    Fo\n  }\n}\n";
    Files.writeString(barFile, barCode, StandardCharsets.UTF_8);
    String barUri = barFile.toUri().toString();

    // Open both files to index them
    serverProxy
        .getTextDocumentService()
        .didOpen(new DidOpenTextDocumentParams(new TextDocumentItem(fooUri, "java", 1, fooCode)));
    serverProxy
        .getTextDocumentService()
        .didOpen(new DidOpenTextDocumentParams(new TextDocumentItem(barUri, "java", 1, barCode)));

    assertNotNull(
        testClient.awaitDiagnostics(fooUri, TIMEOUT_SECONDS),
        "should have received publishDiagnostics for " + fooUri);
    assertNotNull(
        testClient.awaitDiagnostics(barUri, TIMEOUT_SECONDS),
        "should have received publishDiagnostics for " + barUri);

    // Request completion at the end of "Fo" in barCode
    // Line 4: "    Fo" -> line index 4, character 6
    CompletionParams completionParams =
        new CompletionParams(new TextDocumentIdentifier(barUri), new Position(4, 6));

    Either<List<CompletionItem>, CompletionList> result =
        serverProxy
            .getTextDocumentService()
            .completion(completionParams)
            .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

    assertNotNull(result, "completion result should not be null");

    List<CompletionItem> items;
    if (result.isLeft()) {
      items = result.getLeft();
    } else {
      items = result.getRight().getItems();
    }

    assertNotNull(items, "completion items should not be null");
    boolean containsFoo = items.stream().anyMatch(item -> item.getLabel().contains("Foo"));
    assertTrue(
        containsFoo,
        "completion should contain 'Foo', got: "
            + items.stream().map(CompletionItem::getLabel).toList());
  }

  @Test
  void hover_returnsMethodInformation() throws Exception {
    initialize();

    Path dir = Files.createTempDirectory("jvm-pls-lsp-hover");
    Path file = dir.resolve("Hello.java");
    String code =
        """
        public class Hello {
          /** Says hello. */
          String greet(String name) { return name; }
        }
        """;
    Files.writeString(file, code, StandardCharsets.UTF_8);
    String uri = file.toUri().toString();

    serverProxy
        .getTextDocumentService()
        .didOpen(new DidOpenTextDocumentParams(new TextDocumentItem(uri, "java", 1, code)));
    assertNotNull(testClient.awaitDiagnostics(uri, TIMEOUT_SECONDS));

    Hover hover =
        serverProxy
            .getTextDocumentService()
            .hover(new HoverParams(new TextDocumentIdentifier(uri), new Position(2, 10)))
            .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

    assertNotNull(hover);
    assertNotNull(hover.getContents());
  }

  @Test
  void references_workspaceSymbol_andDocumentSymbol_areAvailable() throws Exception {
    initialize();

    Path dir = Files.createTempDirectory("jvm-pls-lsp-refs");
    Path pkgDir = Files.createDirectories(dir.resolve("demo"));
    Path fooFile = pkgDir.resolve("Foo.java");
    String fooCode = "package demo;\n\npublic class Foo {}\n";
    Path barFile = pkgDir.resolve("Bar.java");
    String barCode =
        """
        package demo;

        public class Bar {
          Foo foo = new Foo();
        }
        """;
    Files.writeString(fooFile, fooCode, StandardCharsets.UTF_8);
    Files.writeString(barFile, barCode, StandardCharsets.UTF_8);
    String fooUri = fooFile.toUri().toString();
    String barUri = barFile.toUri().toString();

    serverProxy
        .getTextDocumentService()
        .didOpen(new DidOpenTextDocumentParams(new TextDocumentItem(fooUri, "java", 1, fooCode)));
    serverProxy
        .getTextDocumentService()
        .didOpen(new DidOpenTextDocumentParams(new TextDocumentItem(barUri, "java", 1, barCode)));
    assertNotNull(testClient.awaitDiagnostics(fooUri, TIMEOUT_SECONDS));
    assertNotNull(testClient.awaitDiagnostics(barUri, TIMEOUT_SECONDS));

    List<? extends Location> references =
        serverProxy
            .getTextDocumentService()
            .references(
                new ReferenceParams(
                    new TextDocumentIdentifier(fooUri),
                    new Position(2, 13),
                    new ReferenceContext(false)))
            .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertFalse(references.isEmpty());
    assertTrue(references.stream().anyMatch(location -> barUri.equals(location.getUri())));

    List<Either<SymbolInformation, DocumentSymbol>> documentSymbols =
        serverProxy
            .getTextDocumentService()
            .documentSymbol(new DocumentSymbolParams(new TextDocumentIdentifier(barUri)))
            .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertTrue(
        documentSymbols.stream()
            .filter(Either::isLeft)
            .map(Either::getLeft)
            .anyMatch(symbol -> "Bar".equals(symbol.getName())));

    Either<List<? extends SymbolInformation>, List<? extends WorkspaceSymbol>> workspaceSymbols =
        serverProxy
            .getWorkspaceService()
            .symbol(new WorkspaceSymbolParams("Foo"))
            .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertTrue(
        (workspaceSymbols.isLeft()
                && workspaceSymbols.getLeft().stream()
                    .anyMatch(symbol -> "Foo".equals(symbol.getName())))
            || (workspaceSymbols.isRight()
                && workspaceSymbols.getRight().stream()
                    .anyMatch(symbol -> "Foo".equals(symbol.getName()))));
  }

  @Test
  void codeActions_work() throws Exception {
    initialize();

    Path dir = Files.createTempDirectory("jvm-pls-lsp-actions");
    Path pkgDir = Files.createDirectories(dir.resolve("demo"));
    Path fooFile = pkgDir.resolve("Foo.java");
    String fooCode =
        """
        package demo;

        public class Foo {
          Foo(String value) {}
          void greet(String value) {}
        }
        """;
    Path barFile = pkgDir.resolve("Bar.java");
    String barCode =
        """
        package demo;

        public class Bar {
          void test() {
            Foo foo = new Foo("x");
            foo.greet("x");
            List<String> names = null;
          }
        }
        """;
    Files.writeString(fooFile, fooCode, StandardCharsets.UTF_8);
    Files.writeString(barFile, barCode, StandardCharsets.UTF_8);
    String fooUri = fooFile.toUri().toString();
    String barUri = barFile.toUri().toString();

    serverProxy
        .getTextDocumentService()
        .didOpen(new DidOpenTextDocumentParams(new TextDocumentItem(fooUri, "java", 1, fooCode)));
    serverProxy
        .getTextDocumentService()
        .didOpen(new DidOpenTextDocumentParams(new TextDocumentItem(barUri, "java", 1, barCode)));
    assertNotNull(testClient.awaitDiagnostics(fooUri, TIMEOUT_SECONDS));
    PublishDiagnosticsParams barDiagnostics = testClient.awaitDiagnostics(barUri, TIMEOUT_SECONDS);
    assertNotNull(barDiagnostics);

    List<Either<Command, CodeAction>> actions =
        serverProxy
            .getTextDocumentService()
            .codeAction(
                new CodeActionParams(
                    new TextDocumentIdentifier(barUri),
                    new Range(new Position(6, 8), new Position(6, 8)),
                    new CodeActionContext(barDiagnostics.getDiagnostics())))
            .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertTrue(
        actions.stream()
            .filter(Either::isRight)
            .map(Either::getRight)
            .anyMatch(action -> action.getTitle().contains("Import java.util.List")));
  }

  @Test
  void typeDefinition_andImplementation_work() throws Exception {
    initialize();

    Path dir = Files.createTempDirectory("jvm-pls-lsp-type-def");
    Path pkgDir = Files.createDirectories(dir.resolve("demo"));
    Path apiFile = pkgDir.resolve("Greeter.java");
    String apiCode = "package demo;\n\npublic interface Greeter {}\n";
    Path implFile = pkgDir.resolve("GreeterImpl.java");
    String implCode = "package demo;\n\npublic class GreeterImpl implements Greeter {}\n";
    Path useFile = pkgDir.resolve("Use.java");
    String useCode =
        """
        package demo;

        public class Use {
          Greeter greeter = new GreeterImpl();
        }
        """;
    Files.writeString(apiFile, apiCode, StandardCharsets.UTF_8);
    Files.writeString(implFile, implCode, StandardCharsets.UTF_8);
    Files.writeString(useFile, useCode, StandardCharsets.UTF_8);
    String apiUri = apiFile.toUri().toString();
    String implUri = implFile.toUri().toString();
    String useUri = useFile.toUri().toString();

    serverProxy
        .getTextDocumentService()
        .didOpen(new DidOpenTextDocumentParams(new TextDocumentItem(apiUri, "java", 1, apiCode)));
    serverProxy
        .getTextDocumentService()
        .didOpen(new DidOpenTextDocumentParams(new TextDocumentItem(implUri, "java", 1, implCode)));
    serverProxy
        .getTextDocumentService()
        .didOpen(new DidOpenTextDocumentParams(new TextDocumentItem(useUri, "java", 1, useCode)));
    assertNotNull(testClient.awaitDiagnostics(apiUri, TIMEOUT_SECONDS));
    assertNotNull(testClient.awaitDiagnostics(implUri, TIMEOUT_SECONDS));
    assertNotNull(testClient.awaitDiagnostics(useUri, TIMEOUT_SECONDS));

    Either<List<? extends Location>, List<? extends LocationLink>> typeDefinition =
        serverProxy
            .getTextDocumentService()
            .typeDefinition(
                new TypeDefinitionParams(new TextDocumentIdentifier(useUri), new Position(3, 10)))
            .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertTrue(typeDefinition.isLeft());
    assertEquals(apiUri, typeDefinition.getLeft().getFirst().getUri());

    Either<List<? extends Location>, List<? extends LocationLink>> implementations =
        serverProxy
            .getTextDocumentService()
            .implementation(
                new ImplementationParams(new TextDocumentIdentifier(apiUri), new Position(2, 17)))
            .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertTrue(implementations.isLeft());
    assertTrue(
        implementations.getLeft().stream().anyMatch(location -> implUri.equals(location.getUri())));
  }

  @Test
  void rename_updatesJavaAndGroovyReferences() throws Exception {
    initialize();

    Path dir = Files.createTempDirectory("jvm-pls-lsp-rename");
    Path pkgDir = Files.createDirectories(dir.resolve("demo"));
    Path javaFile = pkgDir.resolve("Foo.java");
    String javaCode =
        """
        package demo;

        public class Foo {
          public Foo() {}
        }
        """;
    Path groovyFile = pkgDir.resolve("UseFoo.groovy");
    String groovyCode =
        """
        package demo

        class UseFoo {
          def build() {
            new Foo()
          }
        }
        """;
    Files.writeString(javaFile, javaCode, StandardCharsets.UTF_8);
    Files.writeString(groovyFile, groovyCode, StandardCharsets.UTF_8);
    String javaUri = javaFile.toUri().toString();
    String groovyUri = groovyFile.toUri().toString();

    serverProxy
        .getTextDocumentService()
        .didOpen(new DidOpenTextDocumentParams(new TextDocumentItem(javaUri, "java", 1, javaCode)));
    serverProxy
        .getTextDocumentService()
        .didOpen(
            new DidOpenTextDocumentParams(
                new TextDocumentItem(groovyUri, "groovy", 1, groovyCode)));
    assertNotNull(testClient.awaitDiagnostics(javaUri, TIMEOUT_SECONDS));
    assertNotNull(testClient.awaitDiagnostics(groovyUri, TIMEOUT_SECONDS));

    Either3<Range, PrepareRenameResult, PrepareRenameDefaultBehavior> prepare =
        serverProxy
            .getTextDocumentService()
            .prepareRename(
                new PrepareRenameParams(new TextDocumentIdentifier(javaUri), new Position(2, 15)))
            .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertNotNull(prepare);

    WorkspaceEdit edit =
        serverProxy
            .getTextDocumentService()
            .rename(
                new RenameParams(
                    new TextDocumentIdentifier(javaUri), new Position(2, 15), "RenamedFoo"))
            .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertNotNull(edit.getChanges());
    assertTrue(edit.getChanges().containsKey(javaUri));
    assertTrue(edit.getChanges().containsKey(groovyUri));
    assertTrue(
        edit.getChanges().get(javaUri).stream()
            .anyMatch(textEdit -> "RenamedFoo".equals(textEdit.getNewText())));
    assertTrue(
        edit.getChanges().get(groovyUri).stream()
            .anyMatch(textEdit -> "RenamedFoo".equals(textEdit.getNewText())));
  }

  @Test
  void callHierarchy_reportsIncomingAndOutgoingCalls() throws Exception {
    initialize();

    Path dir = Files.createTempDirectory("jvm-pls-lsp-call-hierarchy");
    Path pkgDir = Files.createDirectories(dir.resolve("demo"));
    Path file = pkgDir.resolve("Calls.java");
    String code =
        """
        package demo;

        public class Calls {
          void alpha() { beta(); }
          void beta() {}
          void gamma() { beta(); }
        }
        """;
    Files.writeString(file, code, StandardCharsets.UTF_8);
    String uri = file.toUri().toString();

    serverProxy
        .getTextDocumentService()
        .didOpen(new DidOpenTextDocumentParams(new TextDocumentItem(uri, "java", 1, code)));
    assertNotNull(testClient.awaitDiagnostics(uri, TIMEOUT_SECONDS));

    List<CallHierarchyItem> alphaItems =
        serverProxy
            .getTextDocumentService()
            .prepareCallHierarchy(
                new CallHierarchyPrepareParams(new TextDocumentIdentifier(uri), new Position(3, 9)))
            .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertFalse(alphaItems.isEmpty());

    List<CallHierarchyOutgoingCall> outgoingCalls =
        serverProxy
            .getTextDocumentService()
            .callHierarchyOutgoingCalls(new CallHierarchyOutgoingCallsParams(alphaItems.getFirst()))
            .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertTrue(outgoingCalls.stream().anyMatch(call -> "beta".equals(call.getTo().getName())));

    List<CallHierarchyItem> betaItems =
        serverProxy
            .getTextDocumentService()
            .prepareCallHierarchy(
                new CallHierarchyPrepareParams(new TextDocumentIdentifier(uri), new Position(4, 9)))
            .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertFalse(betaItems.isEmpty());

    List<CallHierarchyIncomingCall> incomingCalls =
        serverProxy
            .getTextDocumentService()
            .callHierarchyIncomingCalls(new CallHierarchyIncomingCallsParams(betaItems.getFirst()))
            .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertTrue(incomingCalls.stream().anyMatch(call -> "alpha".equals(call.getFrom().getName())));
    assertTrue(incomingCalls.stream().anyMatch(call -> "gamma".equals(call.getFrom().getName())));
  }

  // -------------------------------------------------------------------------
  // Helper: send initialize + initialized
  // -------------------------------------------------------------------------

  private InitializeResult initialize() throws Exception {
    InitializeParams params = new InitializeParams();
    params.setCapabilities(new ClientCapabilities());
    params.setProcessId((int) ProcessHandle.current().pid());

    InitializeResult result = serverProxy.initialize(params).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

    serverProxy.initialized(new InitializedParams());

    return result;
  }

  private static void closeQuietly(java.io.Closeable closeable) {
    if (closeable != null) {
      try {
        closeable.close();
      } catch (Exception ignored) {
        // best effort
      }
    }
  }

  // -------------------------------------------------------------------------
  // TestLanguageClient: collects diagnostics for assertions
  // -------------------------------------------------------------------------

  private static class TestLanguageClient implements LanguageClient {

    private final CopyOnWriteArrayList<PublishDiagnosticsParams> diagnosticsList =
        new CopyOnWriteArrayList<>();

    @Override
    public void telemetryEvent(Object object) {
      // no-op
    }

    @Override
    public void publishDiagnostics(PublishDiagnosticsParams diagnostics) {
      diagnosticsList.add(diagnostics);
    }

    @Override
    public void showMessage(MessageParams messageParams) {
      // no-op
    }

    @Override
    public CompletableFuture<MessageActionItem> showMessageRequest(
        ShowMessageRequestParams requestParams) {
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public void logMessage(MessageParams message) {
      // no-op
    }

    /** Wait for a publishDiagnostics call for the given URI, polling with timeout. */
    PublishDiagnosticsParams awaitDiagnostics(String uri, int timeoutSeconds) throws Exception {
      long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
      while (System.currentTimeMillis() < deadline) {
        for (PublishDiagnosticsParams params : diagnosticsList) {
          if (params.getUri().equals(uri)) {
            return params;
          }
        }
        Thread.sleep(50);
      }
      return null;
    }
  }
}
