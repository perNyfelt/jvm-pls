package se.alipsa.jvmpls.server;

import org.eclipse.lsp4j.DidChangeConfigurationParams;
import org.eclipse.lsp4j.DidChangeWatchedFilesParams;
import org.eclipse.lsp4j.services.WorkspaceService;

import java.util.function.BooleanSupplier;

public class JvmPlsWorkspaceService implements WorkspaceService {

    private final BooleanSupplier acceptingRequests;

    public JvmPlsWorkspaceService() {
        this(() -> true);
    }

    JvmPlsWorkspaceService(BooleanSupplier acceptingRequests) {
        this.acceptingRequests = acceptingRequests;
    }

    @Override
    public void didChangeConfiguration(DidChangeConfigurationParams params) {
        if (!acceptingRequests.getAsBoolean()) {
            return;
        }
        // will be implemented in Phase 3 (build system integration)
    }

    @Override
    public void didChangeWatchedFiles(DidChangeWatchedFilesParams params) {
        if (!acceptingRequests.getAsBoolean()) {
            return;
        }
        // will be implemented in Phase 3 (build file watching)
    }
}
