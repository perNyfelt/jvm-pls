package se.alipsa.jvmpls.server;

import org.eclipse.lsp4j.DidChangeConfigurationParams;
import org.eclipse.lsp4j.DidChangeWatchedFilesParams;
import org.eclipse.lsp4j.services.WorkspaceService;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public class JvmPlsWorkspaceService implements WorkspaceService {

    private final BooleanSupplier acceptingRequests;
    private final Consumer<Object> configurationChanged;
    private final Consumer<DidChangeWatchedFilesParams> watchedFilesChanged;

    public JvmPlsWorkspaceService() {
        this(() -> true, ignored -> { }, ignored -> { });
    }

    JvmPlsWorkspaceService(BooleanSupplier acceptingRequests,
                           Consumer<Object> configurationChanged,
                           Consumer<DidChangeWatchedFilesParams> watchedFilesChanged) {
        this.acceptingRequests = acceptingRequests;
        this.configurationChanged = configurationChanged;
        this.watchedFilesChanged = watchedFilesChanged;
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
}
