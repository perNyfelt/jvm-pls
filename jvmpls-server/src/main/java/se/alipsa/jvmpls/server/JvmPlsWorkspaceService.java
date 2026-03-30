package se.alipsa.jvmpls.server;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;

import org.eclipse.lsp4j.DidChangeConfigurationParams;
import org.eclipse.lsp4j.DidChangeWatchedFilesParams;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.WorkspaceSymbol;
import org.eclipse.lsp4j.WorkspaceSymbolParams;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.WorkspaceService;

import se.alipsa.jvmpls.core.model.SymbolInfo;

public class JvmPlsWorkspaceService implements WorkspaceService {

  private final BooleanSupplier acceptingRequests;
  private final Consumer<Object> configurationChanged;
  private final Consumer<DidChangeWatchedFilesParams> watchedFilesChanged;
  private final Function<String, java.util.List<SymbolInfo>> workspaceSymbols;

  public JvmPlsWorkspaceService() {
    this(() -> true, ignored -> {}, ignored -> {}, ignored -> java.util.List.of());
  }

  JvmPlsWorkspaceService(
      BooleanSupplier acceptingRequests,
      Consumer<Object> configurationChanged,
      Consumer<DidChangeWatchedFilesParams> watchedFilesChanged,
      Function<String, java.util.List<SymbolInfo>> workspaceSymbols) {
    this.acceptingRequests = acceptingRequests;
    this.configurationChanged = configurationChanged;
    this.watchedFilesChanged = watchedFilesChanged;
    this.workspaceSymbols = workspaceSymbols;
  }

  @Override
  public void didChangeConfiguration(DidChangeConfigurationParams params) {
    if (!acceptingRequests.getAsBoolean()) {
      return;
    }
    configurationChanged.accept(params.getSettings());
  }

  @Override
  public void didChangeWatchedFiles(DidChangeWatchedFilesParams params) {
    if (!acceptingRequests.getAsBoolean()) {
      return;
    }
    watchedFilesChanged.accept(params);
  }

  @Override
  public java.util.concurrent.CompletableFuture<
          Either<
              java.util.List<? extends SymbolInformation>,
              java.util.List<? extends WorkspaceSymbol>>>
      symbol(WorkspaceSymbolParams params) {
    if (!acceptingRequests.getAsBoolean()) {
      return java.util.concurrent.CompletableFuture.completedFuture(
          Either.forLeft(java.util.List.of()));
    }
    return java.util.concurrent.CompletableFuture.completedFuture(
        Either.forLeft(
            workspaceSymbols.apply(params.getQuery()).stream()
                .map(LspTypeConverter::toLspSymbol)
                .toList()));
  }
}
