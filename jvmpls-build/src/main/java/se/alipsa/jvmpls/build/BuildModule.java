package se.alipsa.jvmpls.build;

import java.nio.file.Path;
import java.util.List;

public record BuildModule(String name,
                          Path projectRoot,
                          List<Path> sourceRoots,
                          List<Path> testSourceRoots,
                          List<Path> outputDirectories,
                          List<String> classpathEntries,
                          List<Path> buildFiles) {

  public BuildModule {
    sourceRoots = sourceRoots == null ? List.of() : List.copyOf(sourceRoots);
    testSourceRoots = testSourceRoots == null ? List.of() : List.copyOf(testSourceRoots);
    outputDirectories = outputDirectories == null ? List.of() : List.copyOf(outputDirectories);
    classpathEntries = classpathEntries == null ? List.of() : List.copyOf(classpathEntries);
    buildFiles = buildFiles == null ? List.of() : List.copyOf(buildFiles);
  }
}
