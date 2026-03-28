package se.alipsa.jvmpls.server;

import se.alipsa.jvmpls.core.CoreFacade;
import se.alipsa.jvmpls.core.model.CompletionItem;
import se.alipsa.jvmpls.core.model.Diagnostic;
import se.alipsa.jvmpls.core.model.Location;
import se.alipsa.jvmpls.core.model.Position;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

final class ReloadableCoreFacade implements CoreFacade, AutoCloseable {

  private final AtomicReference<CoreFacade> delegate = new AtomicReference<>();
  private final AtomicReference<AutoCloseable> lifecycle = new AtomicReference<>();
  private final AtomicReference<String> unavailableReason = new AtomicReference<>("Server not initialized");

  boolean isReady() {
    return delegate.get() != null;
  }

  String unavailableReason() {
    return unavailableReason.get();
  }

  synchronized void install(CoreFacade newDelegate,
                            AutoCloseable newLifecycle,
                            String readyMessage) throws Exception {
    Objects.requireNonNull(newDelegate, "newDelegate");
    AutoCloseable previousLifecycle = lifecycle.getAndSet(newLifecycle);
    delegate.set(newDelegate);
    unavailableReason.set(readyMessage == null || readyMessage.isBlank()
        ? "Workspace core is ready" : readyMessage);
    if (previousLifecycle != null && previousLifecycle != newLifecycle) {
      previousLifecycle.close();
    }
  }

  synchronized void clear(String reason) throws Exception {
    AutoCloseable previousLifecycle = lifecycle.getAndSet(null);
    delegate.set(null);
    unavailableReason.set(reason == null || reason.isBlank()
        ? "Server not initialized" : reason);
    if (previousLifecycle != null) {
      previousLifecycle.close();
    }
  }

  @Override
  public void close() throws Exception {
    clear("Server is shut down");
  }

  @Override
  public List<Diagnostic> openFile(String uri, String text) {
    return requireDelegate().openFile(uri, text);
  }

  @Override
  public List<Diagnostic> changeFile(String uri, String text) {
    return requireDelegate().changeFile(uri, text);
  }

  @Override
  public void closeFile(String uri) {
    requireDelegate().closeFile(uri);
  }

  @Override
  public List<Diagnostic> analyze(String uri) {
    return requireDelegate().analyze(uri);
  }

  @Override
  public List<CompletionItem> completions(String uri, Position position) {
    return requireDelegate().completions(uri, position);
  }

  @Override
  public Optional<Location> definition(String uri, Position position) {
    return requireDelegate().definition(uri, position);
  }

  private CoreFacade requireDelegate() {
    CoreFacade current = delegate.get();
    if (current == null) {
      throw new IllegalStateException(unavailableReason());
    }
    return current;
  }
}
