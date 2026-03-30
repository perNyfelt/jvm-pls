package se.alipsa.jvmpls.server;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import se.alipsa.jvmpls.core.model.Position;
import se.alipsa.jvmpls.core.model.Range;
import se.alipsa.jvmpls.core.model.TextEdit;

final class ConfiguredDocumentFormatter implements DocumentFormatter {

  private static final Logger LOG = Logger.getLogger(ConfiguredDocumentFormatter.class.getName());
  private final Supplier<WorkspaceSettings> settingsSupplier;

  ConfiguredDocumentFormatter(Supplier<WorkspaceSettings> settingsSupplier) {
    this.settingsSupplier = settingsSupplier;
  }

  @Override
  public List<TextEdit> format(String uri, String text) {
    WorkspaceSettings settings = settingsSupplier.get();
    if (settings == null) {
      return List.of();
    }
    WorkspaceSettings.FormatterSettings formatter = settings.formatterForUri(uri);
    if (formatter == null || formatter.command().isBlank()) {
      return List.of();
    }
    try {
      String formatted =
          runFormatter(
              uri, text == null ? "" : text, formatter.command(), settings.formatTimeoutMs());
      if (formatted == null || formatted.equals(text)) {
        return List.of();
      }
      return List.of(new TextEdit(fullDocumentRange(text == null ? "" : text), formatted));
    } catch (Exception e) {
      LOG.log(Level.WARNING, "Document formatting failed for " + uri, e);
      return List.of();
    }
  }

  private static String runFormatter(String uri, String text, String command, long timeoutMs)
      throws Exception {
    List<String> args = splitCommand(command);
    if (args.isEmpty()) {
      return null;
    }
    boolean usesTempFile = args.stream().anyMatch(arg -> arg.contains("{file}"));
    if (usesTempFile) {
      Path tempFile = tempFileFor(uri);
      try {
        Files.writeString(tempFile, text, StandardCharsets.UTF_8);
        List<String> resolved =
            args.stream().map(arg -> arg.replace("{file}", tempFile.toString())).toList();
        runProcess(resolved, null, timeoutMs);
        return Files.readString(tempFile, StandardCharsets.UTF_8);
      } finally {
        deleteIfExists(tempFile);
      }
    }
    return runProcess(args, text, timeoutMs);
  }

  private static String runProcess(List<String> args, String stdin, long timeoutMs)
      throws Exception {
    Process process = new ProcessBuilder(args).start();
    if (stdin != null) {
      try (var writer = process.getOutputStream()) {
        writer.write(stdin.getBytes(StandardCharsets.UTF_8));
      }
    }
    boolean finished = process.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
    if (!finished) {
      process.destroyForcibly();
      throw new IllegalStateException("Formatter timed out");
    }
    String stdout = readFully(process.getInputStream());
    String stderr = readFully(process.getErrorStream());
    if (process.exitValue() != 0) {
      throw new IllegalStateException(
          stderr.isBlank() ? "Formatter exited with code " + process.exitValue() : stderr);
    }
    return stdout;
  }

  private static Path tempFileFor(String uri) throws Exception {
    String suffix = ".tmp";
    if (uri != null) {
      int dot = uri.lastIndexOf('.');
      if (dot >= 0) {
        suffix = uri.substring(dot);
      }
    }
    return Files.createTempFile("jvmpls-format-", suffix);
  }

  private static void deleteIfExists(Path tempFile) {
    try {
      Files.deleteIfExists(tempFile);
    } catch (Exception e) {
      LOG.log(Level.FINE, "Failed to delete temp formatter file " + tempFile, e);
    }
  }

  private static String readFully(java.io.InputStream inputStream) throws Exception {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    inputStream.transferTo(buffer);
    return buffer.toString(StandardCharsets.UTF_8);
  }

  private static Range fullDocumentRange(String text) {
    int line = 0;
    int column = 0;
    for (int i = 0; i < text.length(); i++) {
      if (text.charAt(i) == '\n') {
        line++;
        column = 0;
      } else {
        column++;
      }
    }
    return new Range(new Position(0, 0), new Position(line, column));
  }

  private static List<String> splitCommand(String command) {
    ArrayList<String> parts = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    char quote = 0;
    for (int i = 0; i < command.length(); i++) {
      char ch = command.charAt(i);
      if (quote != 0) {
        if (ch == quote) {
          quote = 0;
        } else {
          current.append(ch);
        }
        continue;
      }
      if (ch == '\'' || ch == '"') {
        quote = ch;
        continue;
      }
      if (Character.isWhitespace(ch)) {
        if (current.length() > 0) {
          parts.add(current.toString());
          current.setLength(0);
        }
        continue;
      }
      current.append(ch);
    }
    if (current.length() > 0) {
      parts.add(current.toString());
    }
    return List.copyOf(parts);
  }
}
