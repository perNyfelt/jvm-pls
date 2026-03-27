package se.alipsa.jvmpls.server;

import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.TextDocumentService;
import se.alipsa.jvmpls.core.CoreFacade;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Bridges the LSP4J {@link TextDocumentService} interface to the
 * transport-agnostic {@link CoreFacade}.
 */
public class JvmPlsTextDocumentService implements TextDocumentService {

  private final CoreFacade core;
  private volatile LanguageClient client;

  public JvmPlsTextDocumentService(CoreFacade core) {
    this.core = core;
  }

  /**
   * Wire the language-client proxy so diagnostics can be published.
   * Called from a different thread than the constructor, hence {@code volatile}.
   */
  public void setClient(LanguageClient client) {
    this.client = client;
  }

  // -------------------------------------------------------------------------
  // Notification handlers
  // -------------------------------------------------------------------------

  @Override
  public void didOpen(DidOpenTextDocumentParams params) {
    TextDocumentItem doc = params.getTextDocument();
    List<se.alipsa.jvmpls.core.model.Diagnostic> diags =
        core.openFile(doc.getUri(), doc.getText());
    publishDiagnostics(doc.getUri(), diags);
  }

  @Override
  public void didChange(DidChangeTextDocumentParams params) {
    String uri = params.getTextDocument().getUri();
    List<TextDocumentContentChangeEvent> changes = params.getContentChanges();
    // Full-sync: use the last (or only) content change
    String text = changes.get(changes.size() - 1).getText();
    List<se.alipsa.jvmpls.core.model.Diagnostic> diags = core.changeFile(uri, text);
    publishDiagnostics(uri, diags);
  }

  @Override
  public void didClose(DidCloseTextDocumentParams params) {
    String uri = params.getTextDocument().getUri();
    core.closeFile(uri);
    // Clear diagnostics for the closed file
    publishDiagnostics(uri, Collections.emptyList());
  }

  @Override
  public void didSave(DidSaveTextDocumentParams params) {
    // no-op
  }

  // -------------------------------------------------------------------------
  // Request handlers
  // -------------------------------------------------------------------------

  @Override
  public CompletableFuture<Either<List<CompletionItem>, CompletionList>> completion(
      CompletionParams params) {
    String uri = params.getTextDocument().getUri();
    se.alipsa.jvmpls.core.model.Position corePos =
        LspTypeConverter.toCore(params.getPosition());
    return CompletableFuture.supplyAsync(() -> {
      List<se.alipsa.jvmpls.core.model.CompletionItem> coreItems =
          core.completions(uri, corePos);
      List<CompletionItem> lspItems = LspTypeConverter.toLspCompletionItems(coreItems);
      return Either.<List<CompletionItem>, CompletionList>forLeft(lspItems);
    });
  }

  @Override
  public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> definition(
      DefinitionParams params) {
    String uri = params.getTextDocument().getUri();
    se.alipsa.jvmpls.core.model.Position corePos =
        LspTypeConverter.toCore(params.getPosition());
    return CompletableFuture.supplyAsync(() -> {
      Optional<se.alipsa.jvmpls.core.model.Location> coreLocation =
          core.definition(uri, corePos);
      List<Location> locations = coreLocation
          .map(loc -> List.of(LspTypeConverter.toLsp(loc)))
          .orElse(Collections.emptyList());
      return Either.<List<? extends Location>, List<? extends LocationLink>>forLeft(locations);
    });
  }

  // -------------------------------------------------------------------------
  // Private helpers
  // -------------------------------------------------------------------------

  private void publishDiagnostics(String uri,
      List<se.alipsa.jvmpls.core.model.Diagnostic> diags) {
    if (client != null) {
      client.publishDiagnostics(
          new PublishDiagnosticsParams(uri, LspTypeConverter.toLspDiagnostics(diags)));
    }
  }
}
