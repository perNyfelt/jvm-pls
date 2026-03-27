package se.alipsa.jvmpls.server;

import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.services.*;
import se.alipsa.jvmpls.core.server.CoreServer;
import se.alipsa.jvmpls.core.server.DiagnosticsPublisher;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Main LSP4J {@link LanguageServer} implementation.
 * Handles initialize/shutdown/exit lifecycle and wires up the services.
 */
public class JvmPlsLanguageServer implements LanguageServer, LanguageClientAware {

  private final CoreServer coreServer;
  private final JvmPlsTextDocumentService textDocumentService;
  private final JvmPlsWorkspaceService workspaceService;

  private volatile LanguageClient client;
  private volatile boolean shutdownRequested = false;

  public JvmPlsLanguageServer() {
    this.coreServer = CoreServer.createDefault(DiagnosticsPublisher.NO_OP);
    this.textDocumentService = new JvmPlsTextDocumentService(coreServer);
    this.workspaceService = new JvmPlsWorkspaceService();
  }

  @Override
  public void connect(LanguageClient client) {
    this.client = client;
    textDocumentService.setClient(client);
  }

  @Override
  public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
    ServerCapabilities capabilities = new ServerCapabilities();
    capabilities.setTextDocumentSync(TextDocumentSyncKind.Full);

    CompletionOptions completionOptions = new CompletionOptions();
    completionOptions.setTriggerCharacters(List.of("."));
    capabilities.setCompletionProvider(completionOptions);

    capabilities.setDefinitionProvider(true);

    InitializeResult result = new InitializeResult(capabilities);
    result.setServerInfo(new ServerInfo("jvm-pls", "1.0.0-SNAPSHOT"));

    return CompletableFuture.completedFuture(result);
  }

  @Override
  public void initialized(InitializedParams params) {
    // no-op for now
  }

  @Override
  public CompletableFuture<Object> shutdown() {
    shutdownRequested = true;
    coreServer.close();
    return CompletableFuture.completedFuture(null);
  }

  @Override
  public void exit() {
    System.exit(shutdownRequested ? 0 : 1);
  }

  @Override
  public TextDocumentService getTextDocumentService() {
    return textDocumentService;
  }

  @Override
  public WorkspaceService getWorkspaceService() {
    return workspaceService;
  }
}
