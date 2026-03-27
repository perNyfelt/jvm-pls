package se.alipsa.jvmpls.server;

import org.eclipse.lsp4j.DidChangeConfigurationParams;
import org.eclipse.lsp4j.DidChangeWatchedFilesParams;
import org.eclipse.lsp4j.services.WorkspaceService;

public class JvmPlsWorkspaceService implements WorkspaceService {

    @Override
    public void didChangeConfiguration(DidChangeConfigurationParams params) {
        // will be implemented in Phase 3 (build system integration)
    }

    @Override
    public void didChangeWatchedFiles(DidChangeWatchedFilesParams params) {
        // will be implemented in Phase 3 (build file watching)
    }
}
