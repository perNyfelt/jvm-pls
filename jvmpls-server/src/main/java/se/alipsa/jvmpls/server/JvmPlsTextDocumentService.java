package se.alipsa.jvmpls.server;

import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode;
import org.eclipse.lsp4j.services.TextDocumentService;
import se.alipsa.jvmpls.core.CoreFacade;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Bridges the LSP4J {@link TextDocumentService} interface to the
 * transport-agnostic {@link CoreFacade}.
 */
public class JvmPlsTextDocumentService implements TextDocumentService {

  private static final Logger LOG = Logger.getLogger(JvmPlsTextDocumentService.class.getName());

  private final CoreFacade core;
  private final BooleanSupplier acceptingRequests;

  public JvmPlsTextDocumentService(CoreFacade core) {
    this(core, () -> true);
  }

  JvmPlsTextDocumentService(CoreFacade core, BooleanSupplier acceptingRequests) {
    this.core = core;
    this.acceptingRequests = acceptingRequests;
  }

  // -------------------------------------------------------------------------
  // Notification handlers
  // -------------------------------------------------------------------------

  @Override
  public void didOpen(DidOpenTextDocumentParams params) {
    if (!acceptingRequests.getAsBoolean()) {
      LOG.warning("Ignoring textDocument/didOpen after shutdown");
      return;
    }
    TextDocumentItem doc = params.getTextDocument();
    core.openFile(doc.getUri(), doc.getText());
  }

  @Override
  public void didChange(DidChangeTextDocumentParams params) {
    if (!acceptingRequests.getAsBoolean()) {
      LOG.warning("Ignoring textDocument/didChange after shutdown");
      return;
    }
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
    if (!acceptingRequests.getAsBoolean()) {
      LOG.warning("Ignoring textDocument/didClose after shutdown");
      return;
    }
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
    if (!acceptingRequests.getAsBoolean()) {
      LOG.warning("Rejecting textDocument/completion after shutdown");
      return rejectedAfterShutdown("textDocument/completion");
    }
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
    if (!acceptingRequests.getAsBoolean()) {
      LOG.warning("Rejecting textDocument/definition after shutdown");
      return rejectedAfterShutdown("textDocument/definition");
    }
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

  private static <T> CompletableFuture<T> rejectedAfterShutdown(String method) {
    return CompletableFuture.failedFuture(new ResponseErrorException(
        new ResponseError(
            ResponseErrorCode.InvalidRequest,
            method + " is not available after shutdown",
            null)));
  }
}
