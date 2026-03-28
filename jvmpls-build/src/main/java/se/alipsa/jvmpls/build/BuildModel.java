package se.alipsa.jvmpls.build;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

public record BuildModel(String toolId,
                         Path projectRoot,
                         List<Path> sourceRoots,
                         List<Path> testSourceRoots,
                         List<String> classpathEntries,
                         List<Path> outputDirectories,
                         List<BuildModule> modules,
                         Path targetJdkHome,
                         List<Path> watchedFiles) {

  public BuildModel {
    toolId = Objects.requireNonNull(toolId, "toolId");
    sourceRoots = sourceRoots == null ? List.of() : List.copyOf(sourceRoots);
    testSourceRoots = testSourceRoots == null ? List.of() : List.copyOf(testSourceRoots);
    classpathEntries = classpathEntries == null ? List.of() : List.copyOf(classpathEntries);
    outputDirectories = outputDirectories == null ? List.of() : List.copyOf(outputDirectories);
    modules = modules == null ? List.of() : List.copyOf(modules);
    watchedFiles = watchedFiles == null ? List.of() : List.copyOf(watchedFiles);
  }

  public BuildModel withWatchedFiles(List<Path> additionalWatchedFiles) {
    LinkedHashSet<Path> merged = new LinkedHashSet<>(watchedFiles);
    if (additionalWatchedFiles != null) {
      merged.addAll(additionalWatchedFiles);
    }
    return new BuildModel(toolId, projectRoot, sourceRoots, testSourceRoots, classpathEntries,
        outputDirectories, modules, targetJdkHome, List.copyOf(merged));
  }
}
