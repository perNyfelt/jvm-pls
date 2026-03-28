package se.alipsa.jvmpls.server;

import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

record WorkspaceSettings(String buildToolId,
                         List<String> classpathEntries,
                         Path targetJdkHome) {

  private static final Logger LOG = Logger.getLogger(WorkspaceSettings.class.getName());

  WorkspaceSettings {
    classpathEntries = classpathEntries == null ? List.of() : List.copyOf(classpathEntries);
  }

  static WorkspaceSettings empty() {
    return new WorkspaceSettings(null, List.of(), currentJdkHome());
  }

  static WorkspaceSettings from(Object rawSettings) {
    if (rawSettings == null) {
      return empty();
    }
    if (!(rawSettings instanceof Map<?, ?> settings)) {
      LOG.warning("Ignoring unsupported workspace settings payload of type "
          + rawSettings.getClass().getName());
      return empty();
    }

    String buildToolId = stringValue(settings.get("buildTool"));
    if (buildToolId == null) {
      buildToolId = stringValue(settings.get("buildToolId"));
    }

    LinkedHashSet<String> classpathEntries = new LinkedHashSet<>();
    Object rawClasspath = settings.get("classpath");
    if (rawClasspath == null) {
      rawClasspath = settings.get("classpathEntries");
    }
    if (rawClasspath instanceof Collection<?> entries) {
      for (Object entry : entries) {
        String value = stringValue(entry);
        if (value != null && !value.isBlank()) {
          classpathEntries.add(value);
        }
      }
    }

    String targetJdk = stringValue(settings.get("targetJdkHome"));
    if (targetJdk == null) {
      targetJdk = stringValue(settings.get("jdkHome"));
    }

    return new WorkspaceSettings(
        buildToolId,
        List.copyOf(classpathEntries),
        targetJdk == null || targetJdk.isBlank() ? currentJdkHome() : Path.of(targetJdk));
  }

  boolean hasManualClasspath() {
    return !classpathEntries.isEmpty();
  }

  private static String stringValue(Object value) {
    return value == null ? null : String.valueOf(value);
  }

  private static Path currentJdkHome() {
    String javaHome = System.getProperty("java.home");
    return javaHome == null || javaHome.isBlank() ? null : Path.of(javaHome);
  }
}
