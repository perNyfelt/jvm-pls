package se.alipsa.jvmpls.server;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.jsonrpc.messages.Either3;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode;
import org.eclipse.lsp4j.services.TextDocumentService;

import se.alipsa.jvmpls.core.CoreFacade;

/**
 * Bridges the LSP4J {@link TextDocumentService} interface to the transport-agnostic {@link
 * CoreFacade}.
 */
public class JvmPlsTextDocumentService implements TextDocumentService {

  private static final Logger LOG = Logger.getLogger(JvmPlsTextDocumentService.class.getName());

  private final CoreFacade core;
  private final OpenDocuments openDocuments;
  private final BooleanSupplier acceptingRequests;
  private final BooleanSupplier coreReady;
  private final DocumentFormatter formatter;

  public JvmPlsTextDocumentService(CoreFacade core) {
    this(core, new OpenDocuments(), () -> true, () -> true, DocumentFormatter.disabled());
  }

  public JvmPlsTextDocumentService(CoreFacade core, DocumentFormatter formatter) {
    this(core, new OpenDocuments(), () -> true, () -> true, formatter);
  }

  JvmPlsTextDocumentService(
      CoreFacade core,
      OpenDocuments openDocuments,
      BooleanSupplier acceptingRequests,
      BooleanSupplier coreReady,
      DocumentFormatter formatter) {
    this.core = core;
    this.openDocuments = openDocuments;
    this.acceptingRequests = acceptingRequests;
    this.coreReady = coreReady;
    this.formatter = formatter == null ? DocumentFormatter.disabled() : formatter;
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
    if (!coreReady.getAsBoolean()) {
      LOG.warning("Ignoring textDocument/didOpen before initialization");
      return;
    }
    TextDocumentItem doc = params.getTextDocument();
    openDocuments.open(doc.getUri(), doc.getLanguageId(), doc.getVersion(), doc.getText());
    core.openFile(doc.getUri(), doc.getText());
  }

  @Override
  public void didChange(DidChangeTextDocumentParams params) {
    if (!acceptingRequests.getAsBoolean()) {
      LOG.warning("Ignoring textDocument/didChange after shutdown");
      return;
    }
    if (!coreReady.getAsBoolean()) {
      LOG.warning("Ignoring textDocument/didChange before initialization");
      return;
    }
    String uri = params.getTextDocument().getUri();
    List<TextDocumentContentChangeEvent> changes = params.getContentChanges();
    if (changes == null || changes.isEmpty()) {
      return;
    }
    String text =
        openDocuments.applyIncrementalChanges(uri, params.getTextDocument().getVersion(), changes);
    core.changeFile(uri, text);
  }

  @Override
  public void didClose(DidCloseTextDocumentParams params) {
    if (!acceptingRequests.getAsBoolean()) {
      LOG.warning("Ignoring textDocument/didClose after shutdown");
      return;
    }
    if (!coreReady.getAsBoolean()) {
      LOG.warning("Ignoring textDocument/didClose before initialization");
      return;
    }
    String uri = params.getTextDocument().getUri();
    openDocuments.close(uri);
    core.closeFile(uri);
  }

  @Override
  public void didSave(DidSaveTextDocumentParams params) {
    if (!acceptingRequests.getAsBoolean()) {
      LOG.warning("Ignoring textDocument/didSave after shutdown");
      return;
    }
    if (!coreReady.getAsBoolean()) {
      LOG.warning("Ignoring textDocument/didSave before initialization");
      return;
    }
    if (params == null || params.getTextDocument() == null) {
      return;
    }
    core.analyze(params.getTextDocument().getUri());
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
    if (!coreReady.getAsBoolean()) {
      LOG.warning("Rejecting textDocument/completion before initialization");
      return rejectedUnavailable("textDocument/completion");
    }
    return CompletableFuture.supplyAsync(
        () -> {
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
  public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>>
      declaration(DeclarationParams params) {
    if (!acceptingRequests.getAsBoolean()) {
      LOG.warning("Rejecting textDocument/declaration after shutdown");
      return rejectedAfterShutdown("textDocument/declaration");
    }
    if (!coreReady.getAsBoolean()) {
      LOG.warning("Rejecting textDocument/declaration before initialization");
      return rejectedUnavailable("textDocument/declaration");
    }
    return CompletableFuture.supplyAsync(
        () -> {
          try {
            String uri = params.getTextDocument().getUri();
            se.alipsa.jvmpls.core.model.Position corePos =
                LspTypeConverter.toCore(params.getPosition());
            Optional<se.alipsa.jvmpls.core.model.Location> coreLocation =
                core.definition(uri, corePos);
            List<Location> locations =
                coreLocation
                    .map(loc -> List.of(LspTypeConverter.toLsp(loc)))
                    .orElse(Collections.emptyList());
            return Either.<List<? extends Location>, List<? extends LocationLink>>forLeft(
                locations);
          } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Declaration request failed", e);
            return Either.<List<? extends Location>, List<? extends LocationLink>>forLeft(
                Collections.emptyList());
          }
        });
  }

  @Override
  public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>>
      definition(DefinitionParams params) {
    if (!acceptingRequests.getAsBoolean()) {
      LOG.warning("Rejecting textDocument/definition after shutdown");
      return rejectedAfterShutdown("textDocument/definition");
    }
    if (!coreReady.getAsBoolean()) {
      LOG.warning("Rejecting textDocument/definition before initialization");
      return rejectedUnavailable("textDocument/definition");
    }
    return CompletableFuture.supplyAsync(
        () -> {
          try {
            String uri = params.getTextDocument().getUri();
            se.alipsa.jvmpls.core.model.Position corePos =
                LspTypeConverter.toCore(params.getPosition());
            Optional<se.alipsa.jvmpls.core.model.Location> coreLocation =
                core.definition(uri, corePos);
            List<Location> locations =
                coreLocation
                    .map(loc -> List.of(LspTypeConverter.toLsp(loc)))
                    .orElse(Collections.emptyList());
            return Either.<List<? extends Location>, List<? extends LocationLink>>forLeft(
                locations);
          } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Definition request failed", e);
            return Either.<List<? extends Location>, List<? extends LocationLink>>forLeft(
                Collections.emptyList());
          }
        });
  }

  @Override
  public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>>
      typeDefinition(TypeDefinitionParams params) {
    if (!acceptingRequests.getAsBoolean()) {
      LOG.warning("Rejecting textDocument/typeDefinition after shutdown");
      return rejectedAfterShutdown("textDocument/typeDefinition");
    }
    if (!coreReady.getAsBoolean()) {
      LOG.warning("Rejecting textDocument/typeDefinition before initialization");
      return rejectedUnavailable("textDocument/typeDefinition");
    }
    return CompletableFuture.supplyAsync(
        () -> {
          try {
            String uri = params.getTextDocument().getUri();
            se.alipsa.jvmpls.core.model.Position corePos =
                LspTypeConverter.toCore(params.getPosition());
            Optional<se.alipsa.jvmpls.core.model.Location> coreLocation =
                core.typeDefinition(uri, corePos);
            List<Location> locations =
                coreLocation
                    .map(loc -> List.of(LspTypeConverter.toLsp(loc)))
                    .orElse(Collections.emptyList());
            return Either.<List<? extends Location>, List<? extends LocationLink>>forLeft(
                locations);
          } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Type definition request failed", e);
            return Either.<List<? extends Location>, List<? extends LocationLink>>forLeft(
                Collections.emptyList());
          }
        });
  }

  @Override
  public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>>
      implementation(ImplementationParams params) {
    if (!acceptingRequests.getAsBoolean()) {
      LOG.warning("Rejecting textDocument/implementation after shutdown");
      return rejectedAfterShutdown("textDocument/implementation");
    }
    if (!coreReady.getAsBoolean()) {
      LOG.warning("Rejecting textDocument/implementation before initialization");
      return rejectedUnavailable("textDocument/implementation");
    }
    return CompletableFuture.supplyAsync(
        () -> {
          try {
            String uri = params.getTextDocument().getUri();
            se.alipsa.jvmpls.core.model.Position corePos =
                LspTypeConverter.toCore(params.getPosition());
            List<Location> locations =
                core.implementations(uri, corePos).stream().map(LspTypeConverter::toLsp).toList();
            return Either.<List<? extends Location>, List<? extends LocationLink>>forLeft(
                locations);
          } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Implementation request failed", e);
            return Either.<List<? extends Location>, List<? extends LocationLink>>forLeft(
                Collections.emptyList());
          }
        });
  }

  @Override
  public CompletableFuture<Hover> hover(HoverParams params) {
    if (!acceptingRequests.getAsBoolean()) {
      LOG.warning("Rejecting textDocument/hover after shutdown");
      return rejectedAfterShutdown("textDocument/hover");
    }
    if (!coreReady.getAsBoolean()) {
      LOG.warning("Rejecting textDocument/hover before initialization");
      return rejectedUnavailable("textDocument/hover");
    }
    return CompletableFuture.supplyAsync(
        () -> {
          try {
            String uri = params.getTextDocument().getUri();
            se.alipsa.jvmpls.core.model.Position corePos =
                LspTypeConverter.toCore(params.getPosition());
            return core.hover(uri, corePos).map(LspTypeConverter::toLsp).orElse(null);
          } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Hover request failed", e);
            return null;
          }
        });
  }

  @Override
  public CompletableFuture<SignatureHelp> signatureHelp(SignatureHelpParams params) {
    if (!acceptingRequests.getAsBoolean()) {
      LOG.warning("Rejecting textDocument/signatureHelp after shutdown");
      return rejectedAfterShutdown("textDocument/signatureHelp");
    }
    if (!coreReady.getAsBoolean()) {
      LOG.warning("Rejecting textDocument/signatureHelp before initialization");
      return rejectedUnavailable("textDocument/signatureHelp");
    }
    return CompletableFuture.supplyAsync(
        () -> {
          try {
            String uri = params.getTextDocument().getUri();
            se.alipsa.jvmpls.core.model.Position corePos =
                LspTypeConverter.toCore(params.getPosition());
            return core.signatureHelp(uri, corePos).map(LspTypeConverter::toLsp).orElse(null);
          } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Signature help request failed", e);
            return null;
          }
        });
  }

  @Override
  public CompletableFuture<List<? extends Location>> references(ReferenceParams params) {
    if (!acceptingRequests.getAsBoolean()) {
      LOG.warning("Rejecting textDocument/references after shutdown");
      return rejectedAfterShutdown("textDocument/references");
    }
    if (!coreReady.getAsBoolean()) {
      LOG.warning("Rejecting textDocument/references before initialization");
      return rejectedUnavailable("textDocument/references");
    }
    return CompletableFuture.supplyAsync(
        () -> {
          try {
            String uri = params.getTextDocument().getUri();
            se.alipsa.jvmpls.core.model.Position corePos =
                LspTypeConverter.toCore(params.getPosition());
            boolean includeDeclaration =
                params.getContext() != null && params.getContext().isIncludeDeclaration();
            return core.references(uri, corePos, includeDeclaration).stream()
                .map(LspTypeConverter::toLsp)
                .toList();
          } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "References request failed", e);
            return List.of();
          }
        });
  }

  @Override
  public CompletableFuture<List<? extends DocumentHighlight>> documentHighlight(
      DocumentHighlightParams params) {
    if (!acceptingRequests.getAsBoolean()) {
      LOG.warning("Rejecting textDocument/documentHighlight after shutdown");
      return rejectedAfterShutdown("textDocument/documentHighlight");
    }
    if (!coreReady.getAsBoolean()) {
      LOG.warning("Rejecting textDocument/documentHighlight before initialization");
      return rejectedUnavailable("textDocument/documentHighlight");
    }
    return CompletableFuture.supplyAsync(
        () -> {
          try {
            String uri = params.getTextDocument().getUri();
            se.alipsa.jvmpls.core.model.Position corePos =
                LspTypeConverter.toCore(params.getPosition());
            Optional<se.alipsa.jvmpls.core.model.Location> declaration =
                core.definition(uri, corePos);
            return core.references(uri, corePos, true).stream()
                .filter(location -> uri.equals(location.getUri()))
                .map(location -> toDocumentHighlight(location, declaration))
                .toList();
          } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Document highlight request failed", e);
            return List.of();
          }
        });
  }

  @Override
  public CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>> documentSymbol(
      DocumentSymbolParams params) {
    if (!acceptingRequests.getAsBoolean()) {
      LOG.warning("Rejecting textDocument/documentSymbol after shutdown");
      return rejectedAfterShutdown("textDocument/documentSymbol");
    }
    if (!coreReady.getAsBoolean()) {
      LOG.warning("Rejecting textDocument/documentSymbol before initialization");
      return rejectedUnavailable("textDocument/documentSymbol");
    }
    return CompletableFuture.supplyAsync(
        () -> {
          try {
            return core.documentSymbols(params.getTextDocument().getUri()).stream()
                .map(LspTypeConverter::toLspSymbol)
                .map(Either::<SymbolInformation, DocumentSymbol>forLeft)
                .toList();
          } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Document symbol request failed", e);
            return List.of();
          }
        });
  }

  @Override
  public CompletableFuture<List<Either<Command, CodeAction>>> codeAction(CodeActionParams params) {
    if (!acceptingRequests.getAsBoolean()) {
      LOG.warning("Rejecting textDocument/codeAction after shutdown");
      return rejectedAfterShutdown("textDocument/codeAction");
    }
    if (!coreReady.getAsBoolean()) {
      LOG.warning("Rejecting textDocument/codeAction before initialization");
      return rejectedUnavailable("textDocument/codeAction");
    }
    return CompletableFuture.supplyAsync(
        () -> {
          try {
            String uri = params.getTextDocument().getUri();
            se.alipsa.jvmpls.core.model.Range coreRange =
                LspTypeConverter.toCore(params.getRange());
            List<se.alipsa.jvmpls.core.model.Diagnostic> diagnostics =
                LspTypeConverter.toCoreDiagnostics(
                    params.getContext() == null ? List.of() : params.getContext().getDiagnostics());
            return core.codeActions(uri, coreRange, diagnostics).stream()
                .map(action -> LspTypeConverter.toLsp(uri, action))
                .map(Either::<Command, CodeAction>forRight)
                .toList();
          } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Code action request failed", e);
            return List.of();
          }
        });
  }

  @Override
  public CompletableFuture<List<? extends org.eclipse.lsp4j.TextEdit>> formatting(
      DocumentFormattingParams params) {
    if (!acceptingRequests.getAsBoolean()) {
      LOG.warning("Rejecting textDocument/formatting after shutdown");
      return rejectedAfterShutdown("textDocument/formatting");
    }
    if (!coreReady.getAsBoolean()) {
      LOG.warning("Rejecting textDocument/formatting before initialization");
      return rejectedUnavailable("textDocument/formatting");
    }
    return CompletableFuture.supplyAsync(
        () -> {
          try {
            String uri = params.getTextDocument().getUri();
            String text = openDocuments.text(uri);
            return formatter.format(uri, text).stream().map(LspTypeConverter::toLsp).toList();
          } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Formatting request failed", e);
            return List.of();
          }
        });
  }

  @Override
  public CompletableFuture<Either3<Range, PrepareRenameResult, PrepareRenameDefaultBehavior>>
      prepareRename(PrepareRenameParams params) {
    if (!acceptingRequests.getAsBoolean()) {
      LOG.warning("Rejecting textDocument/prepareRename after shutdown");
      return rejectedAfterShutdown("textDocument/prepareRename");
    }
    if (!coreReady.getAsBoolean()) {
      LOG.warning("Rejecting textDocument/prepareRename before initialization");
      return rejectedUnavailable("textDocument/prepareRename");
    }
    return CompletableFuture.supplyAsync(
        () -> {
          try {
            String uri = params.getTextDocument().getUri();
            se.alipsa.jvmpls.core.model.Position corePos =
                LspTypeConverter.toCore(params.getPosition());
            return core.prepareRename(uri, corePos)
                .map(LspTypeConverter::toLspPrepareRename)
                .orElse(null);
          } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Prepare rename request failed", e);
            return null;
          }
        });
  }

  @Override
  public CompletableFuture<WorkspaceEdit> rename(RenameParams params) {
    if (!acceptingRequests.getAsBoolean()) {
      LOG.warning("Rejecting textDocument/rename after shutdown");
      return rejectedAfterShutdown("textDocument/rename");
    }
    if (!coreReady.getAsBoolean()) {
      LOG.warning("Rejecting textDocument/rename before initialization");
      return rejectedUnavailable("textDocument/rename");
    }
    return CompletableFuture.supplyAsync(
        () -> {
          String uri = params.getTextDocument().getUri();
          se.alipsa.jvmpls.core.model.Position corePos =
              LspTypeConverter.toCore(params.getPosition());
          se.alipsa.jvmpls.core.model.RenamePlan plan =
              core.rename(uri, corePos, params.getNewName()).orElse(null);
          if (plan == null) {
            throw invalidParams("textDocument/rename", "Rename is not supported at this position");
          }
          if (!plan.isValid()) {
            throw invalidParams("textDocument/rename", plan.getConflicts().getFirst().getMessage());
          }
          return LspTypeConverter.toLsp(plan.getWorkspaceEdit());
        });
  }

  @Override
  public CompletableFuture<List<CallHierarchyItem>> prepareCallHierarchy(
      CallHierarchyPrepareParams params) {
    if (!acceptingRequests.getAsBoolean()) {
      LOG.warning("Rejecting callHierarchy/prepare after shutdown");
      return rejectedAfterShutdown("callHierarchy/prepare");
    }
    if (!coreReady.getAsBoolean()) {
      LOG.warning("Rejecting callHierarchy/prepare before initialization");
      return rejectedUnavailable("callHierarchy/prepare");
    }
    return CompletableFuture.supplyAsync(
        () -> {
          try {
            String uri = params.getTextDocument().getUri();
            se.alipsa.jvmpls.core.model.Position corePos =
                LspTypeConverter.toCore(params.getPosition());
            return core.prepareCallHierarchy(uri, corePos).stream()
                .map(LspTypeConverter::toLsp)
                .toList();
          } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Prepare call hierarchy request failed", e);
            return List.of();
          }
        });
  }

  @Override
  public CompletableFuture<List<CallHierarchyIncomingCall>> callHierarchyIncomingCalls(
      CallHierarchyIncomingCallsParams params) {
    if (!acceptingRequests.getAsBoolean()) {
      LOG.warning("Rejecting callHierarchy/incomingCalls after shutdown");
      return rejectedAfterShutdown("callHierarchy/incomingCalls");
    }
    if (!coreReady.getAsBoolean()) {
      LOG.warning("Rejecting callHierarchy/incomingCalls before initialization");
      return rejectedUnavailable("callHierarchy/incomingCalls");
    }
    return CompletableFuture.supplyAsync(
        () -> {
          try {
            String symbolFqn = callHierarchySymbolFqn(params.getItem());
            if (symbolFqn == null) {
              return List.of();
            }
            return core.incomingCalls(symbolFqn).stream().map(LspTypeConverter::toLsp).toList();
          } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Incoming call hierarchy request failed", e);
            return List.of();
          }
        });
  }

  @Override
  public CompletableFuture<List<CallHierarchyOutgoingCall>> callHierarchyOutgoingCalls(
      CallHierarchyOutgoingCallsParams params) {
    if (!acceptingRequests.getAsBoolean()) {
      LOG.warning("Rejecting callHierarchy/outgoingCalls after shutdown");
      return rejectedAfterShutdown("callHierarchy/outgoingCalls");
    }
    if (!coreReady.getAsBoolean()) {
      LOG.warning("Rejecting callHierarchy/outgoingCalls before initialization");
      return rejectedUnavailable("callHierarchy/outgoingCalls");
    }
    return CompletableFuture.supplyAsync(
        () -> {
          try {
            String symbolFqn = callHierarchySymbolFqn(params.getItem());
            if (symbolFqn == null) {
              return List.of();
            }
            return core.outgoingCalls(symbolFqn).stream().map(LspTypeConverter::toLsp).toList();
          } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Outgoing call hierarchy request failed", e);
            return List.of();
          }
        });
  }

  private static <T> CompletableFuture<T> rejectedAfterShutdown(String method) {
    return CompletableFuture.failedFuture(
        new ResponseErrorException(
            new ResponseError(
                ResponseErrorCode.InvalidRequest,
                method + " is not available after shutdown",
                null)));
  }

  private static <T> CompletableFuture<T> rejectedUnavailable(String method) {
    return CompletableFuture.failedFuture(
        new ResponseErrorException(
            new ResponseError(
                ResponseErrorCode.InvalidRequest,
                method + " is not available before initialization completes",
                null)));
  }

  private static ResponseErrorException invalidParams(String method, String message) {
    return new ResponseErrorException(
        new ResponseError(
            ResponseErrorCode.InvalidParams,
            method + ": " + (message == null || message.isBlank() ? "invalid params" : message),
            null));
  }

  private static String callHierarchySymbolFqn(CallHierarchyItem item) {
    if (item == null || item.getData() == null) {
      return null;
    }
    Object data = item.getData();
    if (data instanceof String string) {
      return string;
    }
    String value = String.valueOf(data);
    if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
      value = value.substring(1, value.length() - 1);
    }
    return value.isBlank() ? null : value;
  }

  private static DocumentHighlight toDocumentHighlight(
      se.alipsa.jvmpls.core.model.Location location,
      Optional<se.alipsa.jvmpls.core.model.Location> declaration) {
    DocumentHighlight highlight = new DocumentHighlight();
    highlight.setRange(LspTypeConverter.toLsp(location).getRange());
    highlight.setKind(
        declaration.filter(location::equals).isPresent()
            ? DocumentHighlightKind.Write
            : DocumentHighlightKind.Text);
    return highlight;
  }
}
