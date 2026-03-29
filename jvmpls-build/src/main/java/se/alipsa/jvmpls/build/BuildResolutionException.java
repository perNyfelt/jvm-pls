package se.alipsa.jvmpls.build;

/**
 * Checked exception raised when a build tool plugin cannot resolve a workspace or when build tool
 * selection fails.
 */
public class BuildResolutionException extends Exception {

  public BuildResolutionException(String message) {
    super(message);
  }

  public BuildResolutionException(String message, Throwable cause) {
    super(message, cause);
  }
}
