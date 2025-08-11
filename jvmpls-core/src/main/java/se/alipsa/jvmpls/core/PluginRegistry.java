package se.alipsa.jvmpls.core;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

public final class PluginRegistry {

  private final Map<String, JvmLangPlugin> byId = new ConcurrentHashMap<>();
  private final List<JvmLangPlugin> all = Collections.synchronizedList(new ArrayList<>());
  private final PluginEnvironment env;

  public PluginRegistry(PluginEnvironment env) {
    this.env = env;
    loadViaServiceLoader(); // discover on classpath/modulepath
  }

  public void register(JvmLangPlugin plugin) {
    Objects.requireNonNull(plugin);
    if (byId.putIfAbsent(plugin.id(), plugin) != null) {
      env.log("WARN", "Plugin with id=" + plugin.id() + " already registered; ignoring duplicate.", null);
      return;
    }
    plugin.configure(env);
    all.add(plugin);
    env.log("INFO", "Registered plugin " + plugin.displayName() + " (" + plugin.id() + ")", null);
  }

  public Optional<JvmLangPlugin> byId(String id) { return Optional.ofNullable(byId.get(id)); }

  /** Choose a plugin by asking each one to claim the file; highest score wins. */
  public Optional<JvmLangPlugin> forFile(String fileUri, Supplier<CharSequence> preview) {
    double best = 0.0; JvmLangPlugin winner = null;
    synchronized (all) {
      for (JvmLangPlugin p : all) {
        double score = p.claim(fileUri, preview);
        if (score > best) { best = score; winner = p; }
      }
    }
    return Optional.ofNullable(winner);
  }

  private void loadViaServiceLoader() {
    ServiceLoader<JvmLangPlugin> sl = ServiceLoader.load(JvmLangPlugin.class);
    for (JvmLangPlugin p : sl) register(p);
  }

  public List<JvmLangPlugin> all() { synchronized (all) { return List.copyOf(all); } }
}