package se.alipsa.jvmpls.server;

import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.services.*;
import se.alipsa.jvmpls.core.CoreFacade;
import se.alipsa.jvmpls.core.model.Diagnostic;
import se.alipsa.jvmpls.core.server.CoreServer;
import se.alipsa.jvmpls.core.server.DiagnosticsPublisher;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.IntConsumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Main LSP4J {@link LanguageServer} implementation.
 * Handles initialize/shutdown/exit lifecycle and wires up the services.
 */
public class JvmPlsLanguageServer implements LanguageServer, LanguageClientAware {

  private static final Logger LOG = Logger.getLogger(JvmPlsLanguageServer.class.getName());

  private final AutoCloseable coreLifecycle;
  private final JvmPlsTextDocumentService textDocumentService;
  private final JvmPlsWorkspaceService workspaceService;
  private final ClientDiagnosticsPublisher diagnosticsPublisher;
  private final IntConsumer processExit;
  private volatile boolean shutdownRequested;
  private volatile int exitCode = 1;

  public JvmPlsLanguageServer() {
    this(new ClientDiagnosticsPublisher(), System::exit);
  }

  /**
   * Visible for tests and embeddings that provide a non-publishing {@link CoreFacade}.
   * Do not pass {@link CoreServer}; it already owns diagnostics publishing.
   */
  public JvmPlsLanguageServer(CoreFacade core, AutoCloseable coreLifecycle, IntConsumer processExit) {
    CoreFacade safeCore = Objects.requireNonNull(core, "core");
    if (safeCore instanceof CoreServer) {
      throw new IllegalArgumentException(
          "Passing CoreServer is unsupported here because it already publishes diagnostics");
    }
    this.coreLifecycle = Objects.requireNonNull(coreLifecycle, "coreLifecycle");
    this.diagnosticsPublisher = new ClientDiagnosticsPublisher();
    CoreFacade publishingCore = publishDiagnosticsFrom(safeCore, diagnosticsPublisher);
    this.textDocumentService = new JvmPlsTextDocumentService(publishingCore, this::acceptingRequests);
    this.workspaceService = new JvmPlsWorkspaceService(this::acceptingRequests);
    this.processExit = Objects.requireNonNull(processExit, "processExit");
  }

  private JvmPlsLanguageServer(ClientDiagnosticsPublisher diagnosticsPublisher,
                               IntConsumer processExit) {
    CoreServer coreServer = CoreServer.createDefault(diagnosticsPublisher);
    this.coreLifecycle = coreServer;
    this.textDocumentService = new JvmPlsTextDocumentService(coreServer, this::acceptingRequests);
    this.workspaceService = new JvmPlsWorkspaceService(this::acceptingRequests);
    this.diagnosticsPublisher = diagnosticsPublisher;
    this.processExit = Objects.requireNonNull(processExit, "processExit");
  }

  @Override
  public void connect(LanguageClient client) {
    diagnosticsPublisher.setClient(client);
  }

  @Override
  public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
    ServerCapabilities capabilities = new ServerCapabilities();
    TextDocumentSyncOptions syncOptions = new TextDocumentSyncOptions();
    syncOptions.setOpenClose(true);
    syncOptions.setChange(TextDocumentSyncKind.Full);
    capabilities.setTextDocumentSync(syncOptions);

    CompletionOptions completionOptions = new CompletionOptions();
    completionOptions.setTriggerCharacters(List.of("."));
    capabilities.setCompletionProvider(completionOptions);

    capabilities.setDefinitionProvider(true);

    InitializeResult result = new InitializeResult(capabilities);
    result.setServerInfo(new ServerInfo(ServerMetadata.NAME, ServerMetadata.VERSION));

    return CompletableFuture.completedFuture(result);
  }

  @Override
  public void initialized(InitializedParams params) {
    // Workspace scanning will be added in Phase 3.
  }

  @Override
  public CompletableFuture<Object> shutdown() {
    shutdownRequested = true;
    try {
      coreLifecycle.close();
    } catch (Exception e) {
      LOG.log(Level.SEVERE, "Failed to close core server during shutdown", e);
    }
    return CompletableFuture.completedFuture(null);
  }

  @Override
  public void exit() {
    exitCode = shutdownRequested ? 0 : 1;
    processExit.accept(exitCode);
  }

  /** Returns the exit code after {@link #exit()} has been called. */
  public int getExitCode() {
    return exitCode;
  }

  @Override
  public TextDocumentService getTextDocumentService() {
    return textDocumentService;
  }

  @Override
  public WorkspaceService getWorkspaceService() {
    return workspaceService;
  }

  private boolean acceptingRequests() {
    return !shutdownRequested;
  }

  private static CoreFacade publishDiagnosticsFrom(CoreFacade delegate,
                                                   DiagnosticsPublisher diagnosticsPublisher) {
    return new CoreFacade() {
      @Override
      public List<Diagnostic> openFile(String uri, String text) {
        List<Diagnostic> diagnostics = delegate.openFile(uri, text);
        diagnosticsPublisher.publish(uri, diagnostics);
        return diagnostics;
      }

      @Override
      public List<Diagnostic> changeFile(String uri, String text) {
        List<Diagnostic> diagnostics = delegate.changeFile(uri, text);
        diagnosticsPublisher.publish(uri, diagnostics);
        return diagnostics;
      }

      @Override
      public void closeFile(String uri) {
        delegate.closeFile(uri);
        diagnosticsPublisher.publish(uri, List.of());
      }

      @Override
      public List<Diagnostic> analyze(String uri) {
        List<Diagnostic> diagnostics = delegate.analyze(uri);
        diagnosticsPublisher.publish(uri, diagnostics);
        return diagnostics;
      }

      @Override
      public List<se.alipsa.jvmpls.core.model.CompletionItem> completions(
          String uri, se.alipsa.jvmpls.core.model.Position position) {
        return delegate.completions(uri, position);
      }

      @Override
      public java.util.Optional<se.alipsa.jvmpls.core.model.Location> definition(
          String uri, se.alipsa.jvmpls.core.model.Position position) {
        return delegate.definition(uri, position);
      }
    };
  }

  private static final class ClientDiagnosticsPublisher implements DiagnosticsPublisher {

    private volatile LanguageClient client;

    void setClient(LanguageClient client) {
      this.client = client;
    }

    @Override
    public void publish(String uri, List<se.alipsa.jvmpls.core.model.Diagnostic> diagnostics) {
      LanguageClient currentClient = client;
      int count = diagnostics == null ? 0 : diagnostics.size();
      if (currentClient == null) {
        LOG.warning("Dropping " + count + " diagnostics for " + uri
            + " because no language client is connected");
        return;
      }
      try {
        List<se.alipsa.jvmpls.core.model.Diagnostic> safeDiagnostics =
            diagnostics != null ? diagnostics
                : Collections.<se.alipsa.jvmpls.core.model.Diagnostic>emptyList();
        currentClient.publishDiagnostics(
            new PublishDiagnosticsParams(uri, LspTypeConverter.toLspDiagnostics(safeDiagnostics)));
      } catch (RuntimeException e) {
        LOG.log(Level.SEVERE, "Failed to publish diagnostics for " + uri, e);
      }
    }
  }
}
