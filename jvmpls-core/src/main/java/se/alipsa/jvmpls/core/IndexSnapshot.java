package se.alipsa.jvmpls.core;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import se.alipsa.jvmpls.core.model.Location;
import se.alipsa.jvmpls.core.model.Position;
import se.alipsa.jvmpls.core.model.Range;
import se.alipsa.jvmpls.core.model.SymbolInfo;

/**
 * Persistent snapshot of the symbol index for warm restart. Captures the core symbol data so that
 * startup can begin with a pre-populated index instead of from scratch. Snapshots are optional
 * accelerators — if the snapshot is stale or corrupt, it is deleted and the server falls back to
 * cold-start indexing.
 */
public final class IndexSnapshot {
  private static final Logger LOG = Logger.getLogger(IndexSnapshot.class.getName());
  private static final String SNAPSHOT_VERSION = "1";
  private static final String VERSION_FILE = "snapshot-version.txt";
  private static final String SYMBOLS_FILE = "symbols.dat";
  private static final String HIERARCHY_FILE = "hierarchy.dat";

  private final Path snapshotDir;

  public IndexSnapshot(Path workspaceRoot) {
    this.snapshotDir = workspaceRoot.resolve(".jvmpls").resolve("index");
  }

  /** Save the current state of the symbol index to disk. */
  public void save(SymbolIndex index) {
    try {
      Files.createDirectories(snapshotDir);
      Files.writeString(
          snapshotDir.resolve(VERSION_FILE), SNAPSHOT_VERSION, StandardCharsets.UTF_8);
      writeSymbols(snapshotDir.resolve(SYMBOLS_FILE), index);
      writeHierarchy(snapshotDir.resolve(HIERARCHY_FILE), index);
      LOG.info("Saved index snapshot to " + snapshotDir);
    } catch (IOException e) {
      LOG.log(Level.WARNING, "Failed to save index snapshot", e);
    }
  }

  /**
   * Load snapshot into the given symbol index. Returns true if a valid snapshot was loaded, false
   * otherwise.
   */
  public boolean load(SymbolIndex index) {
    Path versionFile = snapshotDir.resolve(VERSION_FILE);
    if (!Files.exists(versionFile)) {
      return false;
    }

    try {
      String version = Files.readString(versionFile, StandardCharsets.UTF_8).trim();
      if (!SNAPSHOT_VERSION.equals(version)) {
        LOG.info("Snapshot version mismatch, deleting stale snapshot");
        delete();
        return false;
      }
    } catch (IOException e) {
      LOG.log(Level.WARNING, "Failed to read snapshot version", e);
      delete();
      return false;
    }

    try {
      int symbolCount = readSymbols(snapshotDir.resolve(SYMBOLS_FILE), index);
      int hierCount = readHierarchy(snapshotDir.resolve(HIERARCHY_FILE), index);
      LOG.info(
          "Loaded index snapshot: "
              + symbolCount
              + " symbols, "
              + hierCount
              + " hierarchy entries");
      return symbolCount > 0;
    } catch (IOException e) {
      LOG.log(Level.WARNING, "Failed to load index snapshot", e);
      delete();
      return false;
    }
  }

  /** Delete the snapshot files. */
  public void delete() {
    try {
      Files.deleteIfExists(snapshotDir.resolve(SYMBOLS_FILE));
      Files.deleteIfExists(snapshotDir.resolve(HIERARCHY_FILE));
      Files.deleteIfExists(snapshotDir.resolve(VERSION_FILE));
    } catch (IOException e) {
      LOG.log(Level.WARNING, "Failed to delete index snapshot", e);
    }
  }

  // ---------- serialization ----------

  // Symbol format (tab-separated, one per line):
  // fileUri \t languageId \t kind \t fqName \t containerFqName \t locUri \t
  // startLine \t startCol \t endLine \t endCol \t signature \t modifiers(comma-sep)

  private void writeSymbols(Path file, SymbolIndex index) throws IOException {
    // Iterate all declared symbols by file
    // We need to get all file URIs that have declarations. Use a public method if available.
    // SymbolIndex has fileToDecls as a private field. We'll need a method to iterate.
    // Since we can't add methods easily without reading the file, let's use allFileUris().
    List<String> fileUris = index.allFileUris();

    try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
      for (String fileUri : fileUris) {
        List<SymbolInfo> syms = index.declarationsInFile(fileUri);
        for (SymbolInfo sym : syms) {
          writer.write(fileUri);
          writer.write('\t');
          writer.write(sym.getLanguageId());
          writer.write('\t');
          writer.write(sym.getKind().name());
          writer.write('\t');
          writer.write(sym.getFqName());
          writer.write('\t');
          writer.write(sym.getContainerFqName());
          writer.write('\t');
          Location loc = sym.getLocation();
          if (loc != null) {
            writer.write(loc.getUri());
            writer.write('\t');
            writer.write(String.valueOf(loc.getRange().start.line));
            writer.write('\t');
            writer.write(String.valueOf(loc.getRange().start.column));
            writer.write('\t');
            writer.write(String.valueOf(loc.getRange().end.line));
            writer.write('\t');
            writer.write(String.valueOf(loc.getRange().end.column));
          } else {
            writer.write("\t0\t0\t0\t0");
          }
          writer.write('\t');
          writer.write(sym.getSignature() == null ? "" : sym.getSignature());
          writer.write('\t');
          writer.write(String.join(",", sym.getModifiers()));
          writer.newLine();
        }
      }
    }
  }

  private int readSymbols(Path file, SymbolIndex index) throws IOException {
    if (!Files.exists(file)) {
      return 0;
    }
    int count = 0;
    try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
      String line;
      while ((line = reader.readLine()) != null) {
        if (line.isBlank()) {
          continue;
        }
        String[] parts = line.split("\t", -1);
        if (parts.length < 12) {
          continue;
        }
        String fileUri = parts[0];
        String languageId = parts[1];
        SymbolInfo.Kind kind;
        try {
          kind = SymbolInfo.Kind.valueOf(parts[2]);
        } catch (IllegalArgumentException e) {
          continue;
        }
        String fqName = parts[3];
        String containerFqName = parts[4];
        String locUri = parts[5];
        int startLine = Integer.parseInt(parts[6]);
        int startCol = Integer.parseInt(parts[7]);
        int endLine = Integer.parseInt(parts[8]);
        int endCol = Integer.parseInt(parts[9]);
        String signature = parts[10];
        Set<String> modifiers = new LinkedHashSet<>();
        if (!parts[11].isEmpty()) {
          for (String mod : parts[11].split(",")) {
            modifiers.add(mod);
          }
        }

        Location loc =
            new Location(
                locUri,
                new Range(new Position(startLine, startCol), new Position(endLine, endCol)));
        SymbolInfo sym =
            new SymbolInfo(
                languageId,
                kind,
                fqName,
                containerFqName,
                loc,
                signature,
                Set.copyOf(modifiers),
                List.of());
        index.put(fileUri, sym);
        count++;
      }
    }
    return count;
  }

  // Hierarchy format: typeFqn \t supertype1,supertype2,...
  private void writeHierarchy(Path file, SymbolIndex index) throws IOException {
    Map<String, List<String>> hierarchy = index.allDirectSupertypes();
    try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
      for (Map.Entry<String, List<String>> entry : hierarchy.entrySet()) {
        writer.write(entry.getKey());
        writer.write('\t');
        writer.write(String.join(",", entry.getValue()));
        writer.newLine();
      }
    }
  }

  private int readHierarchy(Path file, SymbolIndex index) throws IOException {
    if (!Files.exists(file)) {
      return 0;
    }
    int count = 0;
    try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
      String line;
      while ((line = reader.readLine()) != null) {
        if (line.isBlank()) {
          continue;
        }
        String[] parts = line.split("\t", -1);
        if (parts.length < 2 || parts[1].isEmpty()) {
          continue;
        }
        String typeFqn = parts[0];
        List<String> supertypes = List.of(parts[1].split(","));
        index.reportDirectSupertypes(typeFqn, supertypes);
        count++;
      }
    }
    return count;
  }
}
