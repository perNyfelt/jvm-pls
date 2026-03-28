package se.alipsa.jvmpls.build;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Immutable module-level slice of a {@link BuildModel}.
 * Module entries capture the roots, outputs, classpath, and build files that belong
 * to a concrete subproject inside a larger workspace.
 */
public record BuildModule(String name,
                          Path projectRoot,
                          List<Path> sourceRoots,
                          List<Path> testSourceRoots,
                          List<Path> outputDirectories,
                          List<String> classpathEntries,
                          List<Path> buildFiles) {

  public BuildModule {
    name = Objects.requireNonNull(name, "name");
    projectRoot = Objects.requireNonNull(projectRoot, "projectRoot");
    sourceRoots = sourceRoots == null ? List.of() : List.copyOf(sourceRoots);
    testSourceRoots = testSourceRoots == null ? List.of() : List.copyOf(testSourceRoots);
    outputDirectories = outputDirectories == null ? List.of() : List.copyOf(outputDirectories);
    classpathEntries = classpathEntries == null ? List.of() : List.copyOf(classpathEntries);
    buildFiles = buildFiles == null ? List.of() : List.copyOf(buildFiles);
  }
}
