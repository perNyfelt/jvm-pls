package test.alipsa.jvmpls.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import se.alipsa.jvmpls.server.Main;

class MainTest {

  @Test
  @DisplayName("parseArgs defaults to stdio when no arguments are provided")
  void parseArgs_defaultsToStdioWhenNoArgsAreProvided() {
    assertEquals(Main.Mode.STDIO, Main.parseArgs(new String[0]).mode());
  }

  @Test
  @DisplayName("parseArgs accepts explicit stdio mode")
  void parseArgs_acceptsExplicitStdio() {
    assertEquals(Main.Mode.STDIO, Main.parseArgs(new String[] {"--stdio"}).mode());
  }

  @Test
  @DisplayName("run prints the current version and returns zero")
  void run_printsVersionAndReturnsZero() {
    ByteArrayOutputStream stdout = new ByteArrayOutputStream();
    int exitCode =
        Main.run(
            new String[] {"--version"},
            new ByteArrayInputStream(new byte[0]),
            stdout,
            new ByteArrayOutputStream());

    assertEquals(0, exitCode);
    assertTrue(
        Pattern.compile("^jvm-pls\\s+\\S+" + Pattern.quote(System.lineSeparator()) + "$")
            .matcher(stdout.toString(StandardCharsets.UTF_8))
            .matches());
  }

  @Test
  @DisplayName("run prints help and returns zero")
  void run_printsHelpAndReturnsZero() {
    ByteArrayOutputStream stdout = new ByteArrayOutputStream();
    int exitCode =
        Main.run(
            new String[] {"--help"},
            new ByteArrayInputStream(new byte[0]),
            stdout,
            new ByteArrayOutputStream());

    assertEquals(0, exitCode);
    String output = stdout.toString(StandardCharsets.UTF_8);
    assertTrue(output.contains("Usage: java -jar jvm-pls-"));
    assertTrue(output.contains("-standalone.jar [options]"));
    assertTrue(output.contains("--stdio"));
    assertTrue(output.contains("--version"));
    assertTrue(output.contains("--help"));
  }

  @Test
  @DisplayName("run reports unknown options and returns one")
  void run_reportsUnknownOptionAndReturnsOne() {
    ByteArrayOutputStream stdout = new ByteArrayOutputStream();
    ByteArrayOutputStream stderr = new ByteArrayOutputStream();
    int exitCode =
        Main.run(new String[] {"--bogus"}, new ByteArrayInputStream(new byte[0]), stdout, stderr);

    assertEquals(1, exitCode);
    assertEquals(
        "Unknown option: --bogus" + System.lineSeparator(),
        stderr.toString(StandardCharsets.UTF_8));
    assertTrue(stdout.toString(StandardCharsets.UTF_8).contains("Usage: java -jar"));
  }
}
