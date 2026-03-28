package se.alipsa.jvmpls.build;

import java.nio.file.Path;

/**
 * Service-provider interface for build tool integrations that can detect a workspace,
 * resolve its build model, and expose the resulting classpath/JDK information to the server.
 */
public interface BuildToolPlugin {

  /** Returns the stable identifier used for explicit build tool selection. */
  String id();

  /**
   * Returns the plugin priority used when several build tools apply to the same workspace.
   * Higher values win.
   */
  default int priority() {
    return 0;
  }

  /**
   * Returns whether this plugin can handle the workspace rooted at {@code projectRoot}.
   */
  boolean applies(Path projectRoot);

  /**
   * Resolves the workspace rooted at {@code projectRoot} into a transport-neutral build model.
   *
   * @throws BuildResolutionException if the workspace matches this plugin but the build cannot be resolved
   */
  BuildModel resolve(Path projectRoot) throws BuildResolutionException;
}
