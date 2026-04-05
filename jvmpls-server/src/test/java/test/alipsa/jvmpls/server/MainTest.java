package test.alipsa.jvmpls.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import se.alipsa.jvmpls.server.Main;

class MainTest {

  @Test
  void parseArgs_defaultsToStdioWhenNoArgsAreProvided() {
    assertEquals(Main.Mode.STDIO, Main.parseArgs(new String[0]).mode());
  }

  @Test
  void parseArgs_acceptsExplicitStdio() {
    assertEquals(Main.Mode.STDIO, Main.parseArgs(new String[] {"--stdio"}).mode());
  }

  @Test
  void run_printsVersionAndReturnsZero() {
    ByteArrayOutputStream stdout = new ByteArrayOutputStream();
    int exitCode =
        Main.run(
            new String[] {"--version"},
            new ByteArrayInputStream(new byte[0]),
            stdout,
            new ByteArrayOutputStream());

    assertEquals(0, exitCode);
    assertEquals(
        "jvm-pls 1.0.0-SNAPSHOT" + System.lineSeparator(),
        stdout.toString(StandardCharsets.UTF_8));
  }

  @Test
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
    assertTrue(output.contains("Usage: java -jar jvm-pls-1.0.0-SNAPSHOT-standalone.jar [options]"));
    assertTrue(output.contains("--stdio"));
    assertTrue(output.contains("--version"));
    assertTrue(output.contains("--help"));
  }

  @Test
  void run_reportsUnknownOptionAndReturnsOne() {
    ByteArrayOutputStream stdout = new ByteArrayOutputStream();
    ByteArrayOutputStream stderr = new ByteArrayOutputStream();
    int exitCode =
        Main.run(
            new String[] {"--bogus"},
            new ByteArrayInputStream(new byte[0]),
            stdout,
            stderr);

    assertEquals(1, exitCode);
    assertEquals("Unknown option: --bogus" + System.lineSeparator(), stderr.toString(StandardCharsets.UTF_8));
    assertTrue(stdout.toString(StandardCharsets.UTF_8).contains("Usage: java -jar"));
  }
}
