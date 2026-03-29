package test.alipsa.jvmpls.build.gradle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import se.alipsa.jvmpls.build.BuildModel;
import se.alipsa.jvmpls.build.gradle.GradleBuildPlugin;

class GradleBuildPluginTest {

  @Test
  void resolvesSingleProjectWorkspace() throws Exception {
    Path root = Files.createTempDirectory("jvmpls-gradle-build");
    Files.createDirectories(root.resolve("src/main/java/demo"));
    Files.createDirectories(root.resolve("build/classes/java/main"));
    Files.writeString(
        root.resolve("settings.gradle"), "rootProject.name = 'demo'\n", StandardCharsets.UTF_8);
    Files.writeString(
        root.resolve("build.gradle"),
        """
        plugins {
          id 'java'
        }

        repositories {
          mavenCentral()
        }

        dependencies {
          implementation 'io.github.classgraph:classgraph:4.8.184'
        }
        """,
        StandardCharsets.UTF_8);

    BuildModel model = new GradleBuildPlugin().resolve(root);

    assertEquals("gradle", model.toolId());
    assertTrue(model.sourceRoots().stream().anyMatch(path -> path.endsWith("src/main/java")));
    assertTrue(
        !model.outputDirectories().isEmpty(), "Gradle output directories should be discovered");
    assertTrue(
        model.classpathEntries().stream()
            .anyMatch(entry -> entry.contains("classgraph-4.8.184.jar")));
    assertTrue(model.watchedFiles().contains(root.resolve("build.gradle")));
    assertTrue(model.watchedFiles().contains(root.resolve("settings.gradle")));
  }

  @Test
  void testSourceDetection_usesPathSegmentsInsteadOfSubstringMatches() throws Exception {
    Path root = Files.createTempDirectory("jvmpls-gradle-test-sources");
    Files.createDirectories(root.resolve("src/testutils/java/demo"));
    Files.createDirectories(root.resolve("src/test/java/demo"));
    Files.writeString(
        root.resolve("settings.gradle"), "rootProject.name = 'demo'\n", StandardCharsets.UTF_8);
    Files.writeString(
        root.resolve("build.gradle"),
        """
        plugins {
          id 'java'
        }

        sourceSets {
          main {
            java.srcDirs = ['src/testutils/java']
          }
        }
        """,
        StandardCharsets.UTF_8);

    BuildModel model = new GradleBuildPlugin().resolve(root);

    assertTrue(model.sourceRoots().stream().anyMatch(path -> path.endsWith("src/testutils/java")));
    assertTrue(model.testSourceRoots().stream().anyMatch(path -> path.endsWith("src/test/java")));
  }
}
