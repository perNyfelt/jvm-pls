package test.alipsa.jvmpls.server;

import org.junit.jupiter.api.Test;
import se.alipsa.jvmpls.core.CoreFacade;
import se.alipsa.jvmpls.core.model.CompletionItem;
import se.alipsa.jvmpls.core.model.Diagnostic;
import se.alipsa.jvmpls.core.model.Location;
import se.alipsa.jvmpls.core.model.Position;
import se.alipsa.jvmpls.server.JvmPlsLanguageServer;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JvmPlsLanguageServerLifecycleTest {

  @Test
  void exit_usesFailureCodeWhenShutdownWasNotRequested() {
    AtomicInteger observedExitCode = new AtomicInteger(-1);
    JvmPlsLanguageServer server = new JvmPlsLanguageServer(
        new NoOpCoreFacade(),
        () -> { },
        observedExitCode::set);

    server.exit();

    assertEquals(1, server.getExitCode());
    assertEquals(1, observedExitCode.get());
  }

  @Test
  void shutdown_thenExit_usesSuccessCode() throws Exception {
    AtomicBoolean closed = new AtomicBoolean();
    AtomicInteger observedExitCode = new AtomicInteger(-1);
    JvmPlsLanguageServer server = new JvmPlsLanguageServer(
        new NoOpCoreFacade(),
        () -> closed.set(true),
        observedExitCode::set);

    Object shutdownResult = server.shutdown().get();
    server.exit();

    assertNull(shutdownResult);
    assertTrue(closed.get(), "core lifecycle should be closed during shutdown");
    assertEquals(0, server.getExitCode());
    assertEquals(0, observedExitCode.get());
  }

  @Test
  void shutdown_returnsNormallyWhenCoreCloseFails() throws Exception {
    AtomicInteger observedExitCode = new AtomicInteger(-1);
    JvmPlsLanguageServer server = new JvmPlsLanguageServer(
        new NoOpCoreFacade(),
        () -> { throw new Exception("boom"); },
        observedExitCode::set);

    Object shutdownResult = server.shutdown().get();
    server.exit();

    assertNull(shutdownResult);
    assertEquals(0, server.getExitCode());
    assertEquals(0, observedExitCode.get());
  }

  private static final class NoOpCoreFacade implements CoreFacade {

    @Override
    public List<Diagnostic> openFile(String uri, String text) {
      return List.of();
    }

    @Override
    public List<Diagnostic> changeFile(String uri, String text) {
      return List.of();
    }

    @Override
    public void closeFile(String uri) {
    }

    @Override
    public List<Diagnostic> analyze(String uri) {
      return List.of();
    }

    @Override
    public List<CompletionItem> completions(String uri, Position position) {
      return List.of();
    }

    @Override
    public Optional<Location> definition(String uri, Position position) {
      return Optional.empty();
    }
  }
}
