package se.alipsa.jvmpls.build.maven;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;

import se.alipsa.jvmpls.build.BuildModel;
import se.alipsa.jvmpls.build.BuildModule;
import se.alipsa.jvmpls.build.BuildResolutionException;
import se.alipsa.jvmpls.build.BuildToolPlugin;
import se.alipsa.mavenutils.MavenUtils;

public final class MavenBuildPlugin implements BuildToolPlugin {

  @Override
  public String id() {
    return "maven";
  }

  @Override
  public int priority() {
    return 200;
  }

  @Override
  public boolean applies(Path projectRoot) {
    return projectRoot != null && java.nio.file.Files.exists(projectRoot.resolve("pom.xml"));
  }

  @Override
  public BuildModel resolve(Path projectRoot) throws BuildResolutionException {
    Path root = projectRoot.toAbsolutePath().normalize();
    Path rootPom = root.resolve("pom.xml");
    if (!java.nio.file.Files.exists(rootPom)) {
      throw new BuildResolutionException("No pom.xml found at " + rootPom);
    }

    MavenUtils maven = new MavenUtils();
    Map<Path, Model> modelsByPom = new LinkedHashMap<>();
    collectModuleModels(rootPom, maven, modelsByPom);

    Map<String, BuildModule> reactorModules = new LinkedHashMap<>();
    LinkedHashSet<Path> sourceRoots = new LinkedHashSet<>();
    LinkedHashSet<Path> testSourceRoots = new LinkedHashSet<>();
    LinkedHashSet<Path> outputDirectories = new LinkedHashSet<>();
    LinkedHashSet<String> classpathEntries = new LinkedHashSet<>();
    LinkedHashSet<Path> watchedFiles = new LinkedHashSet<>();

    for (Map.Entry<Path, Model> entry : modelsByPom.entrySet()) {
      Path pom = entry.getKey();
      Path moduleRoot = pom.getParent();
      Model model = entry.getValue();
      if (model == null) {
        continue;
      }

      BuildModule module = moduleFromModel(moduleRoot, model);
      reactorModules.put(reactorKey(model), module);

      sourceRoots.addAll(module.sourceRoots());
      testSourceRoots.addAll(module.testSourceRoots());
      outputDirectories.addAll(module.outputDirectories());
      classpathEntries.addAll(module.outputDirectories().stream().map(Path::toString).toList());
      watchedFiles.addAll(module.buildFiles());
    }

    for (Map.Entry<Path, Model> entry : modelsByPom.entrySet()) {
      Model model = entry.getValue();
      if (model == null) {
        continue;
      }
      for (Dependency dependency : model.getDependencies()) {
        if (!isClasspathDependency(dependency)) {
          continue;
        }
        if (reactorModules.containsKey(reactorKey(dependency))) {
          continue;
        }
        try {
          File artifact =
              maven.resolveArtifact(
                  dependency.getGroupId(),
                  dependency.getArtifactId(),
                  emptyToNull(dependency.getClassifier()),
                  dependency.getType() == null || dependency.getType().isBlank()
                      ? "jar"
                      : dependency.getType(),
                  dependency.getVersion());
          if (artifact != null && artifact.exists()) {
            classpathEntries.add(artifact.getAbsolutePath());
          }
        } catch (Exception e) {
          throw new BuildResolutionException(
              "Failed to resolve Maven dependency "
                  + dependency.getGroupId()
                  + ":"
                  + dependency.getArtifactId()
                  + ":"
                  + dependency.getVersion(),
              e);
        }
      }
    }

    watchedFiles.add(root.resolve(".mvn/maven.config"));
    watchedFiles.add(root.resolve(".mvn/jvm.config"));
    watchedFiles.add(root.resolve(".mvn/toolchains.xml"));

    return new BuildModel(
        id(),
        root,
        List.copyOf(sourceRoots),
        List.copyOf(testSourceRoots),
        List.copyOf(classpathEntries),
        List.copyOf(outputDirectories),
        new ArrayList<>(reactorModules.values()),
        currentJdkHome(),
        watchedFiles.stream().filter(java.nio.file.Files::exists).toList());
  }

  private static void collectModuleModels(
      Path pomFile, MavenUtils maven, Map<Path, Model> modelsByPom)
      throws BuildResolutionException {
    Path normalizedPom = pomFile.toAbsolutePath().normalize();
    if (modelsByPom.containsKey(normalizedPom)) {
      return;
    }
    try {
      Model model = maven.parsePom(normalizedPom.toFile());
      modelsByPom.put(normalizedPom, model);
      Path moduleRoot = normalizedPom.getParent();
      for (String module : model.getModules()) {
        collectModuleModels(moduleRoot.resolve(module).resolve("pom.xml"), maven, modelsByPom);
      }
    } catch (Exception e) {
      throw new BuildResolutionException("Failed to resolve Maven model for " + pomFile, e);
    }
  }

  private static BuildModule moduleFromModel(Path moduleRoot, Model model) {
    org.apache.maven.model.Build build = model.getBuild();
    Path mainSource =
        resolveBuildPath(
            moduleRoot, build == null ? null : build.getSourceDirectory(), "src/main/java");
    Path mainGroovy = moduleRoot.resolve("src/main/groovy");
    Path testSource =
        resolveBuildPath(
            moduleRoot, build == null ? null : build.getTestSourceDirectory(), "src/test/java");
    Path testGroovy = moduleRoot.resolve("src/test/groovy");
    Path output =
        resolveBuildPath(
            moduleRoot, build == null ? null : build.getOutputDirectory(), "target/classes");
    Path testOutput =
        resolveBuildPath(
            moduleRoot,
            build == null ? null : build.getTestOutputDirectory(),
            "target/test-classes");

    LinkedHashSet<Path> sourceRoots = new LinkedHashSet<>();
    if (java.nio.file.Files.exists(mainSource)) {
      sourceRoots.add(mainSource);
    }
    if (java.nio.file.Files.exists(mainGroovy)) {
      sourceRoots.add(mainGroovy);
    }

    LinkedHashSet<Path> testSourceRoots = new LinkedHashSet<>();
    if (java.nio.file.Files.exists(testSource)) {
      testSourceRoots.add(testSource);
    }
    if (java.nio.file.Files.exists(testGroovy)) {
      testSourceRoots.add(testGroovy);
    }

    LinkedHashSet<Path> outputDirectories = new LinkedHashSet<>();
    if (java.nio.file.Files.exists(output)) {
      outputDirectories.add(output);
    }
    if (java.nio.file.Files.exists(testOutput)) {
      outputDirectories.add(testOutput);
    }

    return new BuildModule(
        model.getArtifactId(),
        moduleRoot,
        List.copyOf(sourceRoots),
        List.copyOf(testSourceRoots),
        List.copyOf(outputDirectories),
        outputDirectories.stream().map(Path::toString).toList(),
        List.of(moduleRoot.resolve("pom.xml")));
  }

  private static Path resolveBuildPath(
      Path moduleRoot, String configuredPath, String defaultRelative) {
    String candidate =
        configuredPath == null || configuredPath.isBlank() ? defaultRelative : configuredPath;
    Path path = Path.of(candidate);
    return path.isAbsolute() ? path.normalize() : moduleRoot.resolve(path).normalize();
  }

  private static boolean isClasspathDependency(Dependency dependency) {
    String scope = dependency.getScope();
    return scope == null || scope.isBlank() || "compile".equals(scope) || "runtime".equals(scope);
  }

  private static String reactorKey(Model model) {
    return model.getGroupId() + ":" + model.getArtifactId();
  }

  private static String reactorKey(Dependency dependency) {
    return dependency.getGroupId() + ":" + dependency.getArtifactId();
  }

  private static String emptyToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  private static Path currentJdkHome() {
    String javaHome = System.getProperty("java.home");
    return javaHome == null || javaHome.isBlank() ? null : Path.of(javaHome);
  }
}
