package se.alipsa.jvmpls.server;

import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageClient;

import java.io.InputStream;
import java.io.OutputStream;

public final class Main {

    private static final String VERSION = "1.0.0-SNAPSHOT";

    private Main() {}

    public static void main(String[] args) throws Exception {
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

    static void startStdio(InputStream in, OutputStream out) throws Exception {
        JvmPlsLanguageServer server = new JvmPlsLanguageServer();
        Launcher<LanguageClient> launcher = LSPLauncher.createServerLauncher(server, in, out);
        server.connect(launcher.getRemoteProxy());
        launcher.startListening().get();
    }

    private static void printUsage() {
        System.out.println("Usage: java -jar jvmpls-server.jar [options]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --stdio    Launch LSP server over stdin/stdout (default)");
        System.out.println("  --version  Print version and exit");
        System.out.println("  --help     Print this help and exit");
    }
}
