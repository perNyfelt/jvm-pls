package se.alipsa.jvmpls.server;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageClient;

public final class Main {

  private static final Logger LOG = Logger.getLogger(Main.class.getName());

  private Main() {}

  public static void main(String[] args) {
    System.exit(run(args, System.in, System.out, System.err));
  }

  /**
   * Execute the command-line entry point without terminating the JVM. This is used by {@link
   * #main(String[])} and by tests that need to verify CLI behavior.
   *
   * @param args CLI arguments
   * @param in stdin source for stdio mode
   * @param out stdout sink
   * @param err stderr sink
   * @return the process-style exit code that {@link #main(String[])} would use
   */
  public static int run(String[] args, InputStream in, OutputStream out, OutputStream err) {
    ParsedArgs parsed = parseArgs(args);
    PrintStream stdout = new PrintStream(out, true, StandardCharsets.UTF_8);
    PrintStream stderr = new PrintStream(err, true, StandardCharsets.UTF_8);
    return switch (parsed.mode()) {
      case VERSION -> {
        stdout.println(ServerMetadata.NAME + " " + ServerMetadata.VERSION);
        yield 0;
      }
      case HELP -> {
        printUsage(stdout);
        yield 0;
      }
      case INVALID -> {
        stderr.println(parsed.errorMessage());
        printUsage(stdout);
        yield 1;
      }
      case STDIO -> startStdio(in, out);
    };
  }

  /**
   * Parse CLI arguments into a transport-neutral mode description. The parser is intentionally
   * small: `--stdio` is the default, `--help` and `--version` are terminal modes, and any unknown
   * option is reported as invalid.
   *
   * @param args CLI arguments
   * @return parsed arguments describing the requested execution mode
   */
  public static ParsedArgs parseArgs(String[] args) {
    for (String arg : args) {
      switch (arg) {
        case "--version" -> {
          return new ParsedArgs(Mode.VERSION, null);
        }
        case "--help" -> {
          return new ParsedArgs(Mode.HELP, null);
        }
        case "--stdio" -> {
          // default, no-op
        }
        default -> {
          return new ParsedArgs(Mode.INVALID, "Unknown option: " + arg);
        }
      }
    }
    return new ParsedArgs(Mode.STDIO, null);
  }

  static int startStdio(InputStream in, OutputStream out) {
    try {
      JvmPlsLanguageServer server = new JvmPlsLanguageServer();
      Launcher<LanguageClient> launcher = LSPLauncher.createServerLauncher(server, in, out);
      server.connect(launcher.getRemoteProxy());
      launcher.startListening().get();
      return server.getExitCode();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      LOG.log(Level.SEVERE, "LSP server interrupted while listening on stdio", e);
      return 1;
    } catch (ExecutionException e) {
      Throwable cause = e.getCause() != null ? e.getCause() : e;
      LOG.log(Level.SEVERE, "LSP server stopped unexpectedly while serving stdio", cause);
      return 1;
    } catch (RuntimeException e) {
      LOG.log(Level.SEVERE, "Failed to start or run the LSP server over stdio", e);
      return 1;
    }
  }

  private static void printUsage(PrintStream out) {
    out.println("Usage: java -jar " + ServerMetadata.STANDALONE_JAR + " [options]");
    out.println();
    out.println("Options:");
    out.println("  --stdio    Launch LSP server over stdin/stdout (default)");
    out.println("  --version  Print version and exit");
    out.println("  --help     Print this help and exit");
  }

  /** Supported top-level CLI modes for the standalone server entry point. */
  public enum Mode {
    STDIO,
    VERSION,
    HELP,
    INVALID
  }

  /**
   * Parsed CLI result containing the selected mode and, for invalid input, the message that should
   * be shown to the user.
   */
  public record ParsedArgs(Mode mode, String errorMessage) {}
}
