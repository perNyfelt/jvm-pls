package test.alipsa.jvmpls.build;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import se.alipsa.jvmpls.build.BuildModel;
import se.alipsa.jvmpls.build.BuildModule;
import se.alipsa.jvmpls.build.BuildResolutionException;
import se.alipsa.jvmpls.build.BuildToolPlugin;
import se.alipsa.jvmpls.build.BuildToolRegistry;

class BuildToolRegistryTest {

  @Test
  void explicitSelectionWins() throws Exception {
    BuildToolRegistry registry =
        new BuildToolRegistry(
            List.of(new FakePlugin("maven", 100, true), new FakePlugin("gradle", 50, true)));

    Optional<BuildToolPlugin> selected = registry.select(Path.of("."), "gradle");

    assertTrue(selected.isPresent());
    assertEquals("gradle", selected.get().id());
  }

  @Test
  void highestPriorityApplicablePluginWins() throws Exception {
    BuildToolRegistry registry =
        new BuildToolRegistry(
            List.of(new FakePlugin("maven", 100, true), new FakePlugin("gradle", 50, true)));

    Optional<BuildToolPlugin> selected = registry.select(Path.of("."), null);

    assertTrue(selected.isPresent());
    assertEquals("maven", selected.get().id());
  }

  @Test
  void ambiguousTopPriorityFails() {
    BuildToolRegistry registry =
        new BuildToolRegistry(
            List.of(new FakePlugin("maven", 100, true), new FakePlugin("gradle", 100, true)));

    assertThrows(BuildResolutionException.class, () -> registry.select(Path.of("."), null));
  }

  @Test
  void buildModel_requiresNonNullToolId() {
    assertThrows(
        NullPointerException.class,
        () ->
            new BuildModel(
                null, null, List.of(), List.of(), List.of(), List.of(), List.of(), null,
                List.of()));
  }

  @Test
  void buildModule_requiresNonNullIdentityFields() {
    assertThrows(
        NullPointerException.class,
        () ->
            new BuildModule(
                null, Path.of("."), List.of(), List.of(), List.of(), List.of(), List.of()));
    assertThrows(
        NullPointerException.class,
        () -> new BuildModule("demo", null, List.of(), List.of(), List.of(), List.of(), List.of()));
    assertDoesNotThrow(
        () ->
            new BuildModule(
                "demo", Path.of("."), List.of(), List.of(), List.of(), List.of(), List.of()));
  }

  private record FakePlugin(String id, int priority, boolean applies) implements BuildToolPlugin {

    @Override
    public boolean applies(Path projectRoot) {
      return applies;
    }

    @Override
    public BuildModel resolve(Path projectRoot) {
      throw new UnsupportedOperationException();
    }
  }
}
