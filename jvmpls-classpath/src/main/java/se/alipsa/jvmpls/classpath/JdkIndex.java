package se.alipsa.jvmpls.classpath;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import se.alipsa.jvmpls.core.model.SymbolInfo;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class JdkIndex {

  public ScannedTypeCatalog scan(Path targetJdkHome) {
    Path current = currentJdkHome();
    if (targetJdkHome == null || (current != null && samePath(current, targetJdkHome))) {
      return scanCurrentRuntime();
    }
    Path jmodsDir = targetJdkHome.resolve("jmods");
    if (Files.isDirectory(jmodsDir)) {
      return scanJmods(jmodsDir);
    }
    return scanCurrentRuntime();
  }

  private ScannedTypeCatalog scanCurrentRuntime() {
    ScannedTypeCatalog.Builder builder = ScannedTypeCatalog.builder();
    try {
      var jrt = FileSystems.getFileSystem(URI.create("jrt:/"));
      for (Path moduleRoot : Files.newDirectoryStream(jrt.getPath("/modules"))) {
        try (var paths = Files.walk(moduleRoot)) {
          paths.filter(path -> path.toString().endsWith(".class"))
              .filter(path -> !path.getFileName().toString().equals("module-info.class"))
              .forEach(path -> readClass(path.toUri().toString(), () -> Files.newInputStream(path), builder));
        }
      }
    } catch (IOException ignored) {
      return ScannedTypeCatalog.builder().build();
    }
    return builder.build();
  }

  private ScannedTypeCatalog scanJmods(Path jmodsDir) {
    ScannedTypeCatalog.Builder builder = ScannedTypeCatalog.builder();
    try {
      for (Path jmod : Files.newDirectoryStream(jmodsDir, "*.jmod")) {
        try (ZipFile zipFile = new ZipFile(jmod.toFile())) {
          var entries = zipFile.entries();
          while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (!entry.getName().startsWith("classes/") || !entry.getName().endsWith(".class")
                || entry.getName().endsWith("module-info.class")) {
              continue;
            }
            String resourceUri = "jar:" + jmod.toUri() + "!/" + entry.getName();
            readClass(resourceUri, () -> zipFile.getInputStream(entry), builder);
          }
        }
      }
    } catch (IOException ignored) {
      return ScannedTypeCatalog.builder().build();
    }
    return builder.build();
  }

  private static void readClass(String resourceUri,
                                InputStreamSupplier supplier,
                                ScannedTypeCatalog.Builder builder) {
    try (InputStream inputStream = supplier.open()) {
      ClassReader reader = new ClassReader(inputStream);
      int access = reader.getAccess();
      String className = reader.getClassName().replace('/', '.');
      builder.add(new ScannedTypeDescriptor(
          className,
          packageName(className),
          containerFqName(className),
          kindOf(access),
          resourceUri,
          reader.getSuperName() == null ? null : reader.getSuperName().replace('/', '.'),
          java.util.Arrays.stream(reader.getInterfaces())
              .map(name -> name.replace('/', '.'))
              .toList()));
    } catch (IOException ignored) {
      // skip unreadable class files
    }
  }

  private static SymbolInfo.Kind kindOf(int access) {
    if ((access & Opcodes.ACC_ANNOTATION) != 0) {
      return SymbolInfo.Kind.ANNOTATION;
    }
    if ((access & Opcodes.ACC_INTERFACE) != 0) {
      return SymbolInfo.Kind.INTERFACE;
    }
    if ((access & Opcodes.ACC_ENUM) != 0) {
      return SymbolInfo.Kind.ENUM;
    }
    return SymbolInfo.Kind.CLASS;
  }

  private static String packageName(String fqName) {
    int lastDot = fqName.lastIndexOf('.');
    return lastDot < 0 ? "" : fqName.substring(0, lastDot);
  }

  private static String containerFqName(String fqName) {
    return packageName(fqName);
  }

  private static Path currentJdkHome() {
    String javaHome = System.getProperty("java.home");
    return javaHome == null || javaHome.isBlank() ? null : Path.of(javaHome);
  }

  private static boolean samePath(Path left, Path right) {
    try {
      return left.toRealPath().equals(right.toRealPath());
    } catch (IOException e) {
      return left.normalize().equals(right.normalize());
    }
  }

  @FunctionalInterface
  private interface InputStreamSupplier {
    InputStream open() throws IOException;
  }
}
