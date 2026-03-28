package se.alipsa.jvmpls.build;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.ServiceLoader;

public final class BuildToolRegistry {

  private final List<BuildToolPlugin> plugins;

  public BuildToolRegistry(List<BuildToolPlugin> plugins) {
    this.plugins = plugins == null ? List.of() : List.copyOf(plugins);
  }

  public static BuildToolRegistry createDefault() {
    ServiceLoader<BuildToolPlugin> loader = ServiceLoader.load(BuildToolPlugin.class);
    List<BuildToolPlugin> discovered = new ArrayList<>();
    for (BuildToolPlugin plugin : loader) {
      discovered.add(plugin);
    }
    return new BuildToolRegistry(discovered);
  }

  public List<BuildToolPlugin> all() {
    return plugins;
  }

  public List<BuildToolPlugin> applicable(Path projectRoot) {
    return plugins.stream()
        .filter(plugin -> plugin.applies(projectRoot))
        .sorted(Comparator.comparingInt(BuildToolPlugin::priority).reversed()
            .thenComparing(BuildToolPlugin::id))
        .toList();
  }

  public Optional<BuildToolPlugin> select(Path projectRoot, String explicitId)
      throws BuildResolutionException {
    if (explicitId != null && !explicitId.isBlank()) {
      return Optional.of(plugins.stream()
          .filter(plugin -> explicitId.equals(plugin.id()))
          .findFirst()
          .orElseThrow(() -> new BuildResolutionException(
              "No build tool plugin found for explicit id '" + explicitId + "'")));
    }

    List<BuildToolPlugin> applicable = applicable(projectRoot);
    if (applicable.isEmpty()) {
      return Optional.empty();
    }
    if (applicable.size() > 1
        && applicable.getFirst().priority() == applicable.get(1).priority()) {
      throw new BuildResolutionException(
          "Multiple build tools apply at " + projectRoot + ": "
              + applicable.stream().map(BuildToolPlugin::id).toList());
    }
    return Optional.of(applicable.getFirst());
  }
}
