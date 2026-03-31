package se.alipsa.jvmpls.core;

/**
 * Cooperative cancellation token for long-running operations. Implementations should check {@link
 * #isCanceled()} at stable boundaries (before/after expensive operations) and stop work early when
 * canceled.
 */
public interface CancellationToken {

  /** A token that is never canceled. */
  CancellationToken NONE = () -> false;

  /** Returns true if the operation has been canceled and should stop. */
  boolean isCanceled();

  /**
   * Throws {@link CancelledException} if this token has been canceled. Convenience method for
   * checking at boundaries.
   */
  default void checkCanceled() {
    if (isCanceled()) {
      throw new CancelledException();
    }
  }

  /** Exception thrown when a cancellation check finds the token is canceled. */
  class CancelledException extends RuntimeException {
    public CancelledException() {
      super("Operation canceled");
    }
  }
}
