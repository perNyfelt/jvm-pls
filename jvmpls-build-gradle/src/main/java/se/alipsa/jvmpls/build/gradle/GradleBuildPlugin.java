package se.alipsa.jvmpls.build.gradle;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import org.gradle.tooling.GradleConnector;
import org.gradle.tooling.ProjectConnection;
import org.gradle.tooling.model.build.BuildEnvironment;
import org.gradle.tooling.model.eclipse.EclipseExternalDependency;
import org.gradle.tooling.model.eclipse.EclipseOutputLocation;
import org.gradle.tooling.model.eclipse.EclipseProject;
import org.gradle.tooling.model.eclipse.EclipseSourceDirectory;

import se.alipsa.jvmpls.build.BuildModel;
import se.alipsa.jvmpls.build.BuildModule;
import se.alipsa.jvmpls.build.BuildResolutionException;
import se.alipsa.jvmpls.build.BuildToolPlugin;

public final class GradleBuildPlugin implements BuildToolPlugin {

  @Override
  public String id() {
    return "gradle";
  }

  @Override
  public int priority() {
    return 100;
  }

  @Override
  public boolean applies(Path projectRoot) {
    if (projectRoot == null) {
      return false;
    }
    return Files.exists(projectRoot.resolve("build.gradle"))
        || Files.exists(projectRoot.resolve("build.gradle.kts"))
        || Files.exists(projectRoot.resolve("settings.gradle"))
        || Files.exists(projectRoot.resolve("settings.gradle.kts"));
  }

  @Override
  public BuildModel resolve(Path projectRoot) throws BuildResolutionException {
    Path root = projectRoot.toAbsolutePath().normalize();
    GradleConnector connector = GradleConnector.newConnector().forProjectDirectory(root.toFile());
    if (!hasWrapper(root)) {
      Path gradleHome = findLocalGradleHome();
      if (gradleHome == null) {
        throw new BuildResolutionException(
            "Gradle project at "
                + root
                + " has no wrapper and no local Gradle installation was found");
      }
      connector.useInstallation(gradleHome.toFile());
    }

    try (ProjectConnection connection = connector.connect()) {
      EclipseProject rootProject = connection.getModel(EclipseProject.class);
      BuildEnvironment environment = connection.getModel(BuildEnvironment.class);

      LinkedHashSet<Path> sourceRoots = new LinkedHashSet<>();
      LinkedHashSet<Path> testSourceRoots = new LinkedHashSet<>();
      LinkedHashSet<String> classpathEntries = new LinkedHashSet<>();
      LinkedHashSet<Path> outputDirectories = new LinkedHashSet<>();
      LinkedHashSet<Path> watchedFiles = new LinkedHashSet<>();
      List<BuildModule> modules = new ArrayList<>();

      collectProject(
          rootProject,
          sourceRoots,
          testSourceRoots,
          classpathEntries,
          outputDirectories,
          watchedFiles,
          modules);

      return new BuildModel(
          id(),
          root,
          List.copyOf(sourceRoots),
          List.copyOf(testSourceRoots),
          List.copyOf(classpathEntries),
          List.copyOf(outputDirectories),
          List.copyOf(modules),
          environment.getJava() == null || environment.getJava().getJavaHome() == null
              ? null
              : environment.getJava().getJavaHome().toPath(),
          watchedFiles.stream().filter(Files::exists).toList());
    } catch (RuntimeException e) {
      if (wasInterrupted(e)) {
        Thread.currentThread().interrupt();
      }
      throw new BuildResolutionException("Failed to resolve Gradle project at " + root, e);
    }
  }

  private static void collectProject(
      EclipseProject project,
      LinkedHashSet<Path> sourceRoots,
      LinkedHashSet<Path> testSourceRoots,
      LinkedHashSet<String> classpathEntries,
      LinkedHashSet<Path> outputDirectories,
      LinkedHashSet<Path> watchedFiles,
      List<BuildModule> modules) {
    Path projectDir = project.getProjectDirectory().toPath().toAbsolutePath().normalize();

    LinkedHashSet<Path> moduleSourceRoots = new LinkedHashSet<>();
    LinkedHashSet<Path> moduleTestSourceRoots = new LinkedHashSet<>();
    LinkedHashSet<Path> moduleOutputDirectories = new LinkedHashSet<>();
    LinkedHashSet<String> moduleClasspathEntries = new LinkedHashSet<>();

    for (EclipseSourceDirectory sourceDirectory : project.getSourceDirectories()) {
      Path dir = sourceDirectory.getDirectory().toPath().toAbsolutePath().normalize();
      if (isTestSourceDirectory(projectDir, dir)) {
        testSourceRoots.add(dir);
        moduleTestSourceRoots.add(dir);
      } else {
        sourceRoots.add(dir);
        moduleSourceRoots.add(dir);
      }
    }

    EclipseOutputLocation outputLocation = project.getOutputLocation();
    if (outputLocation != null) {
      Path output = resolveGradlePath(projectDir, outputLocation.getPath());
      outputDirectories.add(output);
      moduleOutputDirectories.add(output);
      classpathEntries.add(output.toString());
      moduleClasspathEntries.add(output.toString());
    }

    for (EclipseExternalDependency dependency : project.getClasspath()) {
      File file = dependency.getFile();
      if (file != null && file.exists()) {
        classpathEntries.add(file.getAbsolutePath());
        moduleClasspathEntries.add(file.getAbsolutePath());
      }
    }

    watchedFiles.add(projectDir.resolve("build.gradle"));
    watchedFiles.add(projectDir.resolve("build.gradle.kts"));
    if (project.getParent() == null) {
      watchedFiles.add(projectDir.resolve("settings.gradle"));
      watchedFiles.add(projectDir.resolve("settings.gradle.kts"));
      watchedFiles.add(projectDir.resolve("gradle.properties"));
      watchedFiles.add(projectDir.resolve("gradle/wrapper/gradle-wrapper.properties"));
      watchedFiles.add(projectDir.resolve("gradle/wrapper/gradle-wrapper.jar"));
    }

    modules.add(
        new BuildModule(
            project.getName(),
            projectDir,
            List.copyOf(moduleSourceRoots),
            List.copyOf(moduleTestSourceRoots),
            List.copyOf(moduleOutputDirectories),
            List.copyOf(moduleClasspathEntries),
            watchedFiles.stream()
                .filter(path -> path.startsWith(projectDir))
                .filter(Files::exists)
                .toList()));

    for (EclipseProject child : project.getChildren()) {
      collectProject(
          child,
          sourceRoots,
          testSourceRoots,
          classpathEntries,
          outputDirectories,
          watchedFiles,
          modules);
    }
  }

  private static Path resolveGradlePath(Path projectDir, String path) {
    if (path == null || path.isBlank()) {
      return projectDir.resolve("build/classes/java/main").normalize();
    }
    Path candidate = Path.of(path.startsWith("/") ? path.substring(1) : path);
    return candidate.isAbsolute()
        ? candidate.normalize()
        : projectDir.resolve(candidate).normalize();
  }

  private static boolean hasWrapper(Path root) {
    return Files.exists(root.resolve("gradlew"))
        && Files.exists(root.resolve("gradle/wrapper/gradle-wrapper.jar"))
        && Files.exists(root.resolve("gradle/wrapper/gradle-wrapper.properties"));
  }

  private static Path findLocalGradleHome() {
    String gradleHome = System.getenv("GRADLE_HOME");
    if (gradleHome != null && !gradleHome.isBlank()) {
      Path path = Path.of(gradleHome);
      if (Files.isDirectory(path)) {
        return path;
      }
    }

    Path sdkman =
        Path.of(System.getProperty("user.home"), ".sdkman", "candidates", "gradle", "current");
    if (Files.isDirectory(sdkman)) {
      return sdkman;
    }
    return null;
  }

  private static boolean isTestSourceDirectory(Path projectDir, Path directory) {
    Path pathToCheck =
        directory.startsWith(projectDir) ? projectDir.relativize(directory) : directory;
    for (Path segment : pathToCheck) {
      if ("test".equalsIgnoreCase(segment.toString())) {
        return true;
      }
    }
    return false;
  }

  private static boolean wasInterrupted(Throwable failure) {
    Throwable current = failure;
    while (current != null) {
      if (current instanceof InterruptedException) {
        return true;
      }
      current = current.getCause();
    }
    return false;
  }
}
