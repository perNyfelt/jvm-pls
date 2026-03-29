package test.alipsa.jvmpls.build.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import se.alipsa.jvmpls.build.BuildModel;
import se.alipsa.jvmpls.build.maven.MavenBuildPlugin;

class MavenBuildPluginTest {

  @Test
  void resolvesSingleModuleWorkspace() throws Exception {
    Path root = Files.createTempDirectory("jvmpls-maven-build");
    Files.createDirectories(root.resolve("src/main/java/demo"));
    Files.createDirectories(root.resolve("target/classes"));
    Files.writeString(
        root.resolve("pom.xml"),
        """
        <project xmlns="http://maven.apache.org/POM/4.0.0">
          <modelVersion>4.0.0</modelVersion>
          <groupId>demo</groupId>
          <artifactId>single</artifactId>
          <version>1.0.0</version>
          <dependencies>
            <dependency>
              <groupId>io.github.classgraph</groupId>
              <artifactId>classgraph</artifactId>
              <version>4.8.184</version>
            </dependency>
          </dependencies>
        </project>
        """,
        StandardCharsets.UTF_8);

    BuildModel model = new MavenBuildPlugin().resolve(root);

    assertEquals("maven", model.toolId());
    assertTrue(model.sourceRoots().contains(root.resolve("src/main/java")));
    assertTrue(model.outputDirectories().contains(root.resolve("target/classes")));
    assertTrue(
        model.classpathEntries().stream()
            .anyMatch(entry -> entry.contains("classgraph-4.8.184.jar")));
    assertTrue(model.watchedFiles().contains(root.resolve("pom.xml")));
  }

  @Test
  void resolvesWorkspaceModulesAndOutputDirectories() throws Exception {
    Path root = Files.createTempDirectory("jvmpls-maven-multi");
    Path moduleA = Files.createDirectories(root.resolve("module-a/target/classes"));
    Path moduleB = Files.createDirectories(root.resolve("module-b/target/classes"));
    Files.writeString(
        root.resolve("pom.xml"),
        """
        <project xmlns="http://maven.apache.org/POM/4.0.0">
          <modelVersion>4.0.0</modelVersion>
          <groupId>demo</groupId>
          <artifactId>root</artifactId>
          <version>1.0.0</version>
          <packaging>pom</packaging>
          <modules>
            <module>module-a</module>
            <module>module-b</module>
          </modules>
        </project>
        """,
        StandardCharsets.UTF_8);
    Files.writeString(
        root.resolve("module-a/pom.xml"),
        """
        <project xmlns="http://maven.apache.org/POM/4.0.0">
          <modelVersion>4.0.0</modelVersion>
          <parent>
            <groupId>demo</groupId>
            <artifactId>root</artifactId>
            <version>1.0.0</version>
          </parent>
          <artifactId>module-a</artifactId>
        </project>
        """,
        StandardCharsets.UTF_8);
    Files.writeString(
        root.resolve("module-b/pom.xml"),
        """
        <project xmlns="http://maven.apache.org/POM/4.0.0">
          <modelVersion>4.0.0</modelVersion>
          <parent>
            <groupId>demo</groupId>
            <artifactId>root</artifactId>
            <version>1.0.0</version>
          </parent>
          <artifactId>module-b</artifactId>
          <dependencies>
            <dependency>
              <groupId>demo</groupId>
              <artifactId>module-a</artifactId>
              <version>1.0.0</version>
            </dependency>
          </dependencies>
        </project>
        """,
        StandardCharsets.UTF_8);

    BuildModel model = new MavenBuildPlugin().resolve(root);

    assertEquals(3, model.modules().size());
    assertTrue(model.classpathEntries().contains(moduleA.toString()));
    assertTrue(model.classpathEntries().contains(moduleB.toString()));
  }
}
