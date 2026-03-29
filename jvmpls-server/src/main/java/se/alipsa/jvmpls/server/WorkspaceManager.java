package se.alipsa.jvmpls.server;

import java.net.URI;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.lsp4j.DidChangeWatchedFilesParams;
import org.eclipse.lsp4j.FileEvent;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.WorkspaceFolder;

import se.alipsa.jvmpls.build.BuildModel;
import se.alipsa.jvmpls.build.BuildResolutionException;
import se.alipsa.jvmpls.build.BuildToolPlugin;
import se.alipsa.jvmpls.build.BuildToolRegistry;
import se.alipsa.jvmpls.core.server.DiagnosticsPublisher;

final class WorkspaceManager {

  private static final Logger LOG = Logger.getLogger(WorkspaceManager.class.getName());
  private static final Set<String> GENERIC_BUILD_FILENAMES =
      Set.of(
          "pom.xml",
          "build.gradle",
          "build.gradle.kts",
          "settings.gradle",
          "settings.gradle.kts",
          "gradle.properties",
          "gradle-wrapper.properties",
          "gradle-wrapper.jar",
          "maven.config",
          "jvm.config",
          "toolchains.xml");

  private final BuildToolRegistry buildToolRegistry;
  private final WorkspaceCoreFactory coreFactory;
  private final ReloadableCoreFacade reloadableCore;
  private final OpenDocuments openDocuments;
  private final DiagnosticsPublisher diagnosticsPublisher;
  private final Consumer<String> warningReporter;

  private volatile WorkspaceSettings workspaceSettings = WorkspaceSettings.empty();
  private volatile Path workspaceRoot;
  private volatile BuildModel currentBuildModel;

  WorkspaceManager(
      BuildToolRegistry buildToolRegistry,
      WorkspaceCoreFactory coreFactory,
      ReloadableCoreFacade reloadableCore,
      OpenDocuments openDocuments,
      DiagnosticsPublisher diagnosticsPublisher,
      Consumer<String> warningReporter) {
    this.buildToolRegistry = buildToolRegistry;
    this.coreFactory = coreFactory;
    this.reloadableCore = reloadableCore;
    this.openDocuments = openDocuments;
    this.diagnosticsPublisher = diagnosticsPublisher;
    this.warningReporter = Objects.requireNonNull(warningReporter, "warningReporter");
  }

  void initialize(InitializeParams params) {
    workspaceSettings = WorkspaceSettings.from(params.getInitializationOptions());
    workspaceRoot = resolveWorkspaceRoot(params);
    refreshWorkspace("initialize");
  }

  void didChangeConfiguration(Object settings) {
    workspaceSettings = WorkspaceSettings.from(settings);
    refreshWorkspace("workspace/didChangeConfiguration");
  }

  void didChangeWatchedFiles(DidChangeWatchedFilesParams params) {
    if (params == null || params.getChanges() == null || params.getChanges().isEmpty()) {
      return;
    }
    if (shouldRefresh(params.getChanges())) {
      refreshWorkspace("workspace/didChangeWatchedFiles");
    }
  }

  private boolean shouldRefresh(List<FileEvent> changes) {
    BuildModel model = currentBuildModel;
    Set<Path> watchedFiles = model == null ? Set.of() : Set.copyOf(model.watchedFiles());
    for (FileEvent change : changes) {
      Path changedPath = toPath(change.getUri());
      if (changedPath == null) {
        continue;
      }
      if (watchedFiles.contains(changedPath)) {
        return true;
      }
      Path fileName = changedPath.getFileName();
      if (fileName != null && GENERIC_BUILD_FILENAMES.contains(fileName.toString())) {
        return true;
      }
    }
    return false;
  }

  private synchronized void refreshWorkspace(String reason) {
    BuildModel buildModel;
    try {
      buildModel = resolveBuildModel();
    } catch (BuildResolutionException e) {
      String message =
          "Workspace resolution failed after "
              + reason
              + "; falling back to JDK-only symbols: "
              + rootCauseMessage(e);
      LOG.log(Level.WARNING, message, e);
      warningReporter.accept(message);
      fallbackToJdkOnly(reason, e);
      return;
    }

    installBuildModel(buildModel, reason);
  }

  private void fallbackToJdkOnly(String reason, Exception cause) {
    try {
      BuildModel fallback =
          new BuildModel(
              "fallback",
              workspaceRoot,
              List.of(),
              List.of(),
              workspaceSettings.classpathEntries(),
              List.of(),
              List.of(),
              workspaceSettings.targetJdkHome(),
              List.of());
      installBuildModel(fallback, reason);
      LOG.info(() -> "Loaded fallback workspace core after " + reason);
    } catch (Exception fallbackFailure) {
      String unavailableReason =
          "Workspace initialization failed after "
              + reason
              + ": "
              + rootCauseMessage(fallbackFailure)
              + ". Original resolution error: "
              + rootCauseMessage(cause);
      reloadableCore.setUnavailableReason(unavailableReason);
      LOG.log(Level.SEVERE, "Failed to create fallback workspace core", fallbackFailure);
      warningReporter.accept(unavailableReason);
      throw asIllegalState("Failed to create fallback workspace core", fallbackFailure);
    }
  }

  private void installBuildModel(BuildModel buildModel, String reason) {
    WorkspaceCoreFactory.CoreInstance nextCore =
        coreFactory.create(
            buildModel.classpathEntries(), buildModel.targetJdkHome(), diagnosticsPublisher);
    boolean installed = false;
    try {
      openDocuments.replayInto(nextCore.core());
      reloadableCore.install(
          nextCore.core(), nextCore.lifecycle(), "Workspace core ready for " + buildModel.toolId());
      installed = true;
      currentBuildModel = buildModel;
      LOG.info(
          () ->
              "Loaded workspace using "
                  + buildModel.toolId()
                  + " at "
                  + buildModel.projectRoot()
                  + " with "
                  + buildModel.classpathEntries().size()
                  + " classpath entries"
                  + " after "
                  + reason);
    } catch (RuntimeException e) {
      LOG.log(Level.SEVERE, "Workspace core installation failed after " + reason, e);
      throw e;
    } catch (Exception e) {
      LOG.log(Level.SEVERE, "Workspace core installation failed after " + reason, e);
      throw new IllegalStateException("Failed to install workspace core", e);
    } finally {
      if (!installed) {
        closeQuietly(nextCore.lifecycle(), "Discarding uninstalled workspace core");
      }
    }
  }

  private BuildModel resolveBuildModel() throws BuildResolutionException {
    if (workspaceSettings.hasManualClasspath()) {
      return new BuildModel(
          "manual",
          workspaceRoot,
          List.of(),
          List.of(),
          workspaceSettings.classpathEntries(),
          List.of(),
          List.of(),
          workspaceSettings.targetJdkHome(),
          List.of());
    }

    if (workspaceRoot == null) {
      return new BuildModel(
          "default",
          null,
          List.of(),
          List.of(),
          List.of(),
          List.of(),
          List.of(),
          workspaceSettings.targetJdkHome(),
          List.of());
    }

    Optional<BuildToolPlugin> selected =
        buildToolRegistry.select(workspaceRoot, workspaceSettings.buildToolId());
    if (selected.isEmpty()) {
      return new BuildModel(
          "default",
          workspaceRoot,
          List.of(),
          List.of(),
          List.of(),
          List.of(),
          List.of(),
          workspaceSettings.targetJdkHome(),
          List.of());
    }

    BuildToolPlugin plugin = selected.get();
    BuildModel resolved = plugin.resolve(workspaceRoot);
    LinkedHashSet<Path> watchedFiles = new LinkedHashSet<>(resolved.watchedFiles());
    watchedFiles.addAll(defaultWatchedFiles(workspaceRoot));
    return resolved.withWatchedFiles(List.copyOf(watchedFiles));
  }

  private static List<Path> defaultWatchedFiles(Path root) {
    return List.of(
        root.resolve("pom.xml"),
        root.resolve("build.gradle"),
        root.resolve("build.gradle.kts"),
        root.resolve("settings.gradle"),
        root.resolve("settings.gradle.kts"),
        root.resolve("gradle.properties"),
        root.resolve("gradle/wrapper/gradle-wrapper.properties"),
        root.resolve("gradle/wrapper/gradle-wrapper.jar"),
        root.resolve(".mvn/maven.config"),
        root.resolve(".mvn/jvm.config"),
        root.resolve(".mvn/toolchains.xml"));
  }

  private Path resolveWorkspaceRoot(InitializeParams params) {
    if (params.getRootUri() != null && !params.getRootUri().isBlank()) {
      return toWorkspacePath(params.getRootUri());
    }
    if (params.getWorkspaceFolders() != null && !params.getWorkspaceFolders().isEmpty()) {
      WorkspaceFolder workspaceFolder = params.getWorkspaceFolders().getFirst();
      return workspaceFolder == null ? null : toWorkspacePath(workspaceFolder.getUri());
    }
    return null;
  }

  private Path toWorkspacePath(String uri) {
    if (uri == null || uri.isBlank()) {
      return null;
    }
    try {
      URI parsed = URI.create(uri);
      if (!"file".equalsIgnoreCase(parsed.getScheme())) {
        throw new IllegalArgumentException("Only file:// workspace URIs are supported: " + uri);
      }
      return Path.of(parsed).toAbsolutePath().normalize();
    } catch (RuntimeException e) {
      String message = "Ignoring unsupported workspace URI " + uri + ": " + rootCauseMessage(e);
      LOG.log(Level.WARNING, message, e);
      warningReporter.accept(message);
      return null;
    }
  }

  private static Path toPath(String uri) {
    if (uri == null || uri.isBlank()) {
      return null;
    }
    try {
      return Path.of(URI.create(uri)).toAbsolutePath().normalize();
    } catch (Exception e) {
      LOG.log(Level.WARNING, "Failed to convert workspace URI to path: " + uri, e);
      return null;
    }
  }

  private static void closeQuietly(AutoCloseable lifecycle, String message) {
    if (lifecycle == null) {
      return;
    }
    try {
      lifecycle.close();
    } catch (Exception e) {
      LOG.log(Level.WARNING, message, e);
    }
  }

  private static String rootCauseMessage(Throwable throwable) {
    Throwable current = throwable;
    while (current.getCause() != null) {
      current = current.getCause();
    }
    String message = current.getMessage();
    return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
  }

  private static IllegalStateException asIllegalState(String message, Exception cause) {
    return cause instanceof IllegalStateException existing
        ? existing
        : new IllegalStateException(message, cause);
  }
}
