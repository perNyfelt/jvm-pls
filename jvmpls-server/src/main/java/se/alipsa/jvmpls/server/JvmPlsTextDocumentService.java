package se.alipsa.jvmpls.server;

import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.TextDocumentService;
import se.alipsa.jvmpls.core.CoreFacade;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Bridges the LSP4J {@link TextDocumentService} interface to the
 * transport-agnostic {@link CoreFacade}.
 */
public class JvmPlsTextDocumentService implements TextDocumentService {

  private static final Logger LOG = Logger.getLogger(JvmPlsTextDocumentService.class.getName());

  private final CoreFacade core;

  public JvmPlsTextDocumentService(CoreFacade core) {
    this.core = core;
  }

  // -------------------------------------------------------------------------
  // Notification handlers
  // -------------------------------------------------------------------------

  @Override
  public void didOpen(DidOpenTextDocumentParams params) {
    TextDocumentItem doc = params.getTextDocument();
    core.openFile(doc.getUri(), doc.getText());
  }

  @Override
  public void didChange(DidChangeTextDocumentParams params) {
    String uri = params.getTextDocument().getUri();
    List<TextDocumentContentChangeEvent> changes = params.getContentChanges();
    if (changes == null || changes.isEmpty()) {
      return;
    }
    // Full-sync: use the last (or only) content change
    String text = changes.get(changes.size() - 1).getText();
    core.changeFile(uri, text);
  }

  @Override
  public void didClose(DidCloseTextDocumentParams params) {
    String uri = params.getTextDocument().getUri();
    core.closeFile(uri);
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
    return CompletableFuture.supplyAsync(() -> {
      try {
        String uri = params.getTextDocument().getUri();
        se.alipsa.jvmpls.core.model.Position corePos =
            LspTypeConverter.toCore(params.getPosition());
        List<se.alipsa.jvmpls.core.model.CompletionItem> coreItems =
            core.completions(uri, corePos);
        List<CompletionItem> lspItems = LspTypeConverter.toLspCompletionItems(coreItems);
        return Either.<List<CompletionItem>, CompletionList>forLeft(lspItems);
      } catch (RuntimeException e) {
        LOG.log(Level.SEVERE, "Completion request failed", e);
        return Either.<List<CompletionItem>, CompletionList>forLeft(Collections.emptyList());
      }
    });
  }

  @Override
  public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> definition(
      DefinitionParams params) {
    return CompletableFuture.supplyAsync(() -> {
      try {
        String uri = params.getTextDocument().getUri();
        se.alipsa.jvmpls.core.model.Position corePos =
            LspTypeConverter.toCore(params.getPosition());
        Optional<se.alipsa.jvmpls.core.model.Location> coreLocation =
            core.definition(uri, corePos);
        List<Location> locations = coreLocation
            .map(loc -> List.of(LspTypeConverter.toLsp(loc)))
            .orElse(Collections.emptyList());
        return Either.<List<? extends Location>, List<? extends LocationLink>>forLeft(locations);
      } catch (RuntimeException e) {
        LOG.log(Level.SEVERE, "Definition request failed", e);
        return Either.<List<? extends Location>, List<? extends LocationLink>>forLeft(
            Collections.emptyList());
      }
    });
  }
}
