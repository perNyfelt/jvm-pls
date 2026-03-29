package se.alipsa.jvmpls.server;

import java.nio.file.Path;
import java.util.List;

import se.alipsa.jvmpls.core.CoreFacade;
import se.alipsa.jvmpls.core.server.CoreServer;
import se.alipsa.jvmpls.core.server.DiagnosticsPublisher;

final class WorkspaceCoreFactory {

  CoreInstance create(
      List<String> classpathEntries,
      Path targetJdkHome,
      DiagnosticsPublisher diagnosticsPublisher) {
    CoreServer coreServer =
        CoreServer.createDefault(diagnosticsPublisher, classpathEntries, targetJdkHome);
    return new CoreInstance(coreServer, coreServer);
  }

  record CoreInstance(CoreFacade core, AutoCloseable lifecycle) {}
}
