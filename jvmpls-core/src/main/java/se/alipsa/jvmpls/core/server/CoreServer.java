package se.alipsa.jvmpls.core.server;

import se.alipsa.jvmpls.core.*;
import se.alipsa.jvmpls.core.model.*;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * In-process server façade for super-fast local usage.
 * - Delegates to CoreEngine
 * - Publishes diagnostics via DiagnosticsPublisher
 */
public final class CoreServer implements CoreFacade, AutoCloseable {

  private final CoreEngine engine;
  private final DiagnosticsPublisher publisher;

  // for lifecycle management if we created the executor
  private final Executor executor;
  private final boolean ownsExecutor;

  private CoreServer(CoreEngine engine, DiagnosticsPublisher publisher, Executor executor, boolean ownsExecutor) {
    this.engine = Objects.requireNonNull(engine);
    this.publisher = Objects.requireNonNullElse(publisher, DiagnosticsPublisher.NO_OP);
    this.executor = executor;
    this.ownsExecutor = ownsExecutor;
  }

  /** Build a CoreServer with sensible defaults and plugins discovered via ServiceLoader. */
  public static CoreServer createDefault(DiagnosticsPublisher publisher) {
    return createDefault(publisher, runtimeClasspath(), currentJdkHome());
  }

  /** Build a CoreServer with explicit classpath/JDK configuration for external symbol resolution. */
  public static CoreServer createDefault(DiagnosticsPublisher publisher,
                                         List<String> classpath,
                                         Path targetJdkHome) {
    SymbolIndex index = new SymbolIndex();
    DocumentStore docs = new DocumentStore();
    DependencyGraph graph = new DependencyGraph();
    Executor executor = Executors.newVirtualThreadPerTaskExecutor();
    boolean owns = true;

    registerExternalProviders(index, classpath, targetJdkHome);
    PluginEnvironment env = new DefaultPluginEnvironment(index, executor, classpath);
    PluginRegistry registry = new PluginRegistry(env);

    CoreEngine engine = new CoreEngine(registry, index, docs, graph, executor);
    return new CoreServer(engine, publisher, executor, owns);
  }

  /** Advanced factory in case you want to supply your own pieces (tests, custom exec/logging, etc.). */
  public static CoreServer create(PluginRegistry registry,
                                  SymbolIndex index,
                                  DocumentStore docs,
                                  DependencyGraph graph,
                                  Executor executor,
                                  DiagnosticsPublisher publisher) {
    return create(registry, index, docs, graph, executor, runtimeClasspath(), currentJdkHome(), publisher);
  }

  public static CoreServer create(PluginRegistry registry,
                                  SymbolIndex index,
                                  DocumentStore docs,
                                  DependencyGraph graph,
                                  Executor executor,
                                  List<String> classpath,
                                  Path targetJdkHome,
                                  DiagnosticsPublisher publisher) {
    registerExternalProviders(index, classpath, targetJdkHome);
    PluginEnvironment env = new DefaultPluginEnvironment(index, executor, classpath);
    CoreEngine engine = new CoreEngine(registry, index, docs, graph, executor);
    return new CoreServer(engine, publisher, executor, false);
  }

  // --- CoreFacade (delegates + publishes diagnostics) -------------------------------------------

  @Override
  public List<Diagnostic> openFile(String uri, String text) {
    List<Diagnostic> diags = engine.openFile(uri, text);
    publisher.publish(uri, diags);
    return diags;
  }

  @Override
  public List<Diagnostic> changeFile(String uri, String text) {
    List<Diagnostic> diags = engine.changeFile(uri, text);
    publisher.publish(uri, diags);
    return diags;
  }

  @Override
  public void closeFile(String uri) {
    engine.closeFile(uri);
    publisher.publish(uri, List.of()); // clear diagnostics
  }

  @Override
  public List<Diagnostic> analyze(String uri) {
    List<Diagnostic> diags = engine.analyze(uri);
    publisher.publish(uri, diags);
    return diags;
  }

  @Override
  public List<CompletionItem> completions(String uri, Position position) {
    return engine.completions(uri, position);
  }

  @Override
  public Optional<Location> definition(String uri, Position position) {
    return engine.definition(uri, position);
  }

  // --- Lifecycle --------------------------------------------------------------------------------

  @Override
  public void close() {
    if (ownsExecutor && executor instanceof ExecutorService es) {
      es.shutdown();
    }
  }

  private static void registerExternalProviders(SymbolIndex index,
                                                List<String> classpath,
                                                Path targetJdkHome) {
    SymbolProviderContext context = new SymbolProviderContext(classpath, targetJdkHome);
    ServiceLoader<SymbolProviderFactory> loader = ServiceLoader.load(SymbolProviderFactory.class);
    for (SymbolProviderFactory factory : loader) {
      for (SymbolProvider provider : factory.createProviders(context)) {
        index.registerProvider(provider);
      }
    }
  }

  private static List<String> runtimeClasspath() {
    String classpath = System.getProperty("java.class.path", "");
    if (classpath.isBlank()) {
      return List.of();
    }
    return java.util.Arrays.stream(classpath.split(java.io.File.pathSeparator))
        .filter(entry -> entry != null && !entry.isBlank())
        .distinct()
        .toList();
  }

  private static Path currentJdkHome() {
    String javaHome = System.getProperty("java.home");
    return javaHome == null || javaHome.isBlank() ? null : Path.of(javaHome);
  }
}
