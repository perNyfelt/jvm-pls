package se.alipsa.jvmpls.build;

import java.nio.file.Path;

public interface BuildToolPlugin {

  String id();

  default int priority() {
    return 0;
  }

  boolean applies(Path projectRoot);

  BuildModel resolve(Path projectRoot) throws BuildResolutionException;
}
