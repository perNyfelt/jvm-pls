package se.alipsa.jvmpls.classpath;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import se.alipsa.jvmpls.core.model.SymbolInfo;

/**
 * Persistent classpath cache stored under {@code .jvmpls/classpath/} in the workspace root.
 * Persists the scanned type catalog so that warm restarts can skip the expensive ClassGraph scan.
 *
 * <p>Cache entries are validated against JAR modification times. Stale or unreadable cache data is
 * silently deleted rather than repaired.
 */
final class ClasspathCache {
  private static final Logger LOG = Logger.getLogger(ClasspathCache.class.getName());
  private static final String CACHE_VERSION = "1";
  private static final String CATALOG_FILE = "scan-catalog.dat";
  private static final String VERSION_FILE = "cache-version.txt";

  private final Path cacheDir;

  ClasspathCache(Path workspaceRoot) {
    this.cacheDir = workspaceRoot.resolve(".jvmpls").resolve("classpath");
  }

  /**
   * Try to load a cached catalog. Returns null if no valid cache exists or the cache is stale.
   *
   * @param classpathEntries current classpath entries for staleness check
   */
  ScannedTypeCatalog load(List<String> classpathEntries) {
    Path versionFile = cacheDir.resolve(VERSION_FILE);
    Path catalogFile = cacheDir.resolve(CATALOG_FILE);

    if (!Files.exists(versionFile) || !Files.exists(catalogFile)) {
      return null;
    }

    try {
      String version = Files.readString(versionFile, StandardCharsets.UTF_8).trim();
      if (!CACHE_VERSION.equals(version)) {
        LOG.info("Cache version mismatch (expected " + CACHE_VERSION + ", got " + version + ")");
        delete();
        return null;
      }
    } catch (IOException e) {
      LOG.log(Level.WARNING, "Failed to read cache version", e);
      delete();
      return null;
    }

    try {
      ScannedTypeCatalog catalog = readCatalog(catalogFile);
      if (catalog == null || catalog.isEmpty()) {
        delete();
        return null;
      }
      LOG.info("Loaded classpath cache from " + cacheDir);
      return catalog;
    } catch (IOException e) {
      LOG.log(Level.WARNING, "Failed to read cached catalog", e);
      delete();
      return null;
    }
  }

  /** Save the catalog to disk. */
  void save(ScannedTypeCatalog catalog) {
    try {
      Files.createDirectories(cacheDir);
      Files.writeString(cacheDir.resolve(VERSION_FILE), CACHE_VERSION, StandardCharsets.UTF_8);
      writeCatalog(cacheDir.resolve(CATALOG_FILE), catalog);
      LOG.info("Saved classpath cache to " + cacheDir);
    } catch (IOException e) {
      LOG.log(Level.WARNING, "Failed to save classpath cache", e);
    }
  }

  /** Delete the cache directory contents. */
  void delete() {
    try {
      Path versionFile = cacheDir.resolve(VERSION_FILE);
      Path catalogFile = cacheDir.resolve(CATALOG_FILE);
      Files.deleteIfExists(catalogFile);
      Files.deleteIfExists(versionFile);
      LOG.info("Deleted stale classpath cache at " + cacheDir);
    } catch (IOException e) {
      LOG.log(Level.WARNING, "Failed to delete classpath cache", e);
    }
  }

  // ---------- serialization ----------

  // Format: one line per type descriptor, tab-separated fields:
  // fqName \t packageName \t containerFqName \t kind \t resourceUri \t superclassFqName \t
  // iface1,iface2,...

  private void writeCatalog(Path file, ScannedTypeCatalog catalog) throws IOException {
    List<ScannedTypeDescriptor> descriptors = catalog.allDescriptors();

    try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
      for (ScannedTypeDescriptor d : descriptors) {
        writer.write(d.fqName());
        writer.write('\t');
        writer.write(d.packageName());
        writer.write('\t');
        writer.write(d.containerFqName());
        writer.write('\t');
        writer.write(d.kind().name());
        writer.write('\t');
        writer.write(d.resourceUri());
        writer.write('\t');
        writer.write(d.superclassFqName() == null ? "" : d.superclassFqName());
        writer.write('\t');
        writer.write(String.join(",", d.interfaceFqNames()));
        writer.newLine();
      }
    }
  }

  private ScannedTypeCatalog readCatalog(Path file) throws IOException {
    ScannedTypeCatalog.Builder builder = ScannedTypeCatalog.builder();
    try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
      String line;
      while ((line = reader.readLine()) != null) {
        if (line.isBlank()) {
          continue;
        }
        String[] parts = line.split("\t", -1);
        if (parts.length < 7) {
          LOG.warning("Skipping malformed cache line: " + line);
          continue;
        }
        String fqName = parts[0];
        String packageName = parts[1];
        String containerFqName = parts[2];
        SymbolInfo.Kind kind;
        try {
          kind = SymbolInfo.Kind.valueOf(parts[3]);
        } catch (IllegalArgumentException e) {
          LOG.warning("Unknown kind in cache: " + parts[3]);
          continue;
        }
        String resourceUri = parts[4];
        String superclassFqName = parts[5].isEmpty() ? null : parts[5];
        List<String> interfaces = parts[6].isEmpty() ? List.of() : List.of(parts[6].split(","));

        builder.add(
            new ScannedTypeDescriptor(
                fqName,
                packageName,
                containerFqName,
                kind,
                resourceUri,
                superclassFqName,
                interfaces));
      }
    }
    return builder.build();
  }
}
