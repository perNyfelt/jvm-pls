package se.alipsa.jvmpls.server;

import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageClient;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutionException;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class Main {

    private static final Logger LOG = Logger.getLogger(Main.class.getName());
    private static final String VERSION = "1.0.0-SNAPSHOT";

    private Main() {}

    public static void main(String[] args) {
        for (String arg : args) {
            switch (arg) {
                case "--version" -> {
                    System.out.println("jvm-pls " + VERSION);
                    return;
                }
                case "--help" -> {
                    printUsage();
                    return;
                }
                case "--stdio" -> {} // default, no-op
                default -> {
                    System.err.println("Unknown option: " + arg);
                    printUsage();
                    System.exit(1);
                }
            }
        }

        startStdio(System.in, System.out);
    }

    static void startStdio(InputStream in, OutputStream out) {
        try {
            JvmPlsLanguageServer server = new JvmPlsLanguageServer();
            Launcher<LanguageClient> launcher = LSPLauncher.createServerLauncher(server, in, out);
            server.connect(launcher.getRemoteProxy());
            launcher.startListening().get();
            System.exit(server.getExitCode());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.log(Level.SEVERE, "LSP server interrupted while listening on stdio", e);
            System.exit(1);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            LOG.log(Level.SEVERE, "LSP server stopped unexpectedly while serving stdio", cause);
            System.exit(1);
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Failed to start or run the LSP server over stdio", e);
            System.exit(1);
        }
    }

    private static void printUsage() {
        System.out.println("Usage: java -jar jvmpls-server-" + VERSION + "-standalone.jar [options]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --stdio    Launch LSP server over stdin/stdout (default)");
        System.out.println("  --version  Print version and exit");
        System.out.println("  --help     Print this help and exit");
    }
}
