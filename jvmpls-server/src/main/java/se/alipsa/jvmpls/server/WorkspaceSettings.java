package se.alipsa.jvmpls.server;

import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

record WorkspaceSettings(
    String buildToolId,
    List<String> classpathEntries,
    Path targetJdkHome,
    FormatterSettings javaFormatter,
    FormatterSettings groovyFormatter,
    long formatTimeoutMs) {

  private static final Logger LOG = Logger.getLogger(WorkspaceSettings.class.getName());

  WorkspaceSettings {
    classpathEntries = classpathEntries == null ? List.of() : List.copyOf(classpathEntries);
    javaFormatter = javaFormatter == null ? FormatterSettings.disabled() : javaFormatter;
    groovyFormatter = groovyFormatter == null ? FormatterSettings.disabled() : groovyFormatter;
    formatTimeoutMs = formatTimeoutMs <= 0 ? 10_000 : formatTimeoutMs;
  }

  static WorkspaceSettings empty() {
    return new WorkspaceSettings(
        null,
        List.of(),
        currentJdkHome(),
        FormatterSettings.disabled(),
        FormatterSettings.disabled(),
        10_000);
  }

  static WorkspaceSettings from(Object rawSettings) {
    if (rawSettings == null) {
      return empty();
    }
    if (!(rawSettings instanceof Map<?, ?> settings)) {
      LOG.warning(
          "Ignoring unsupported workspace settings payload of type "
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

    Map<?, ?> format = mapValue(settings.get("format"));
    FormatterSettings javaFormatter =
        formatterSettings(
            settings, format == null ? null : mapValue(format.get("java")), "jvmpls.format.java.");
    FormatterSettings groovyFormatter =
        formatterSettings(
            settings,
            format == null ? null : mapValue(format.get("groovy")),
            "jvmpls.format.groovy.");
    long formatTimeoutMs =
        longValue(
            settings.get("formatTimeoutMs"),
            longValue(settings.get("jvmpls.format.timeoutMs"), 10_000L));

    return new WorkspaceSettings(
        buildToolId,
        List.copyOf(classpathEntries),
        targetJdk == null || targetJdk.isBlank() ? currentJdkHome() : Path.of(targetJdk),
        javaFormatter,
        groovyFormatter,
        formatTimeoutMs);
  }

  boolean hasManualClasspath() {
    return !classpathEntries.isEmpty();
  }

  FormatterSettings formatterForUri(String uri) {
    if (uri == null) {
      return FormatterSettings.disabled();
    }
    String lower = uri.toLowerCase();
    if (lower.endsWith(".java")) {
      return javaFormatter;
    }
    if (lower.endsWith(".groovy")
        || lower.endsWith(".gvy")
        || lower.endsWith(".gy")
        || lower.endsWith(".gsh")) {
      return groovyFormatter;
    }
    return FormatterSettings.disabled();
  }

  private static String stringValue(Object value) {
    return value == null ? null : String.valueOf(value);
  }

  private static Map<?, ?> mapValue(Object value) {
    return value instanceof Map<?, ?> map ? map : null;
  }

  private static long longValue(Object value, long fallback) {
    if (value instanceof Number number) {
      return number.longValue();
    }
    String string = stringValue(value);
    if (string == null || string.isBlank()) {
      return fallback;
    }
    try {
      return Long.parseLong(string);
    } catch (NumberFormatException ignored) {
      return fallback;
    }
  }

  private static FormatterSettings formatterSettings(
      Map<?, ?> rootSettings, Map<?, ?> nestedSettings, String prefix) {
    String provider =
        firstNonBlank(
            stringValue(rootSettings.get(prefix + "provider")),
            nestedSettings == null ? null : stringValue(nestedSettings.get("provider")));
    String command =
        firstNonBlank(
            stringValue(rootSettings.get(prefix + "command")),
            nestedSettings == null ? null : stringValue(nestedSettings.get("command")));
    return new FormatterSettings(provider, command);
  }

  private static String firstNonBlank(String left, String right) {
    if (left != null && !left.isBlank()) {
      return left;
    }
    return right;
  }

  private static Path currentJdkHome() {
    String javaHome = System.getProperty("java.home");
    return javaHome == null || javaHome.isBlank() ? null : Path.of(javaHome);
  }

  record FormatterSettings(String provider, String command) {
    FormatterSettings {
      provider = provider == null ? "" : provider;
      command = command == null ? "" : command;
    }

    static FormatterSettings disabled() {
      return new FormatterSettings("", "");
    }

    boolean isEnabled() {
      return !command.isBlank() || !provider.isBlank();
    }
  }
}
