package test.alipsa.jvmpls.bench;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Generates synthetic Java and Groovy source files for benchmarking. Produces files with realistic
 * structure: one top-level class per file with 3-5 methods, 2-4 fields, and optional
 * extends/implements relationships.
 */
final class SyntheticWorkspace {

  record SourceFile(String uri, String content, String language) {}

  private SyntheticWorkspace() {}

  /**
   * Generate a workspace of the given size. Roughly 90% Java, 10% Groovy. About 20% of files extend
   * another workspace type. About 10% contain cross-language references.
   */
  static List<SourceFile> generate(int fileCount) {
    Random rng = new Random(42); // deterministic seed for reproducible benchmarks
    List<SourceFile> files = new ArrayList<>();
    List<String> javaClassNames = new ArrayList<>();
    List<String> groovyClassNames = new ArrayList<>();

    for (int i = 0; i < fileCount; i++) {
      boolean isGroovy = rng.nextDouble() < 0.10;
      String className = (isGroovy ? "GClass" : "JClass") + i;
      String pkg = "bench.pkg" + (i % 10);

      if (isGroovy) {
        groovyClassNames.add(pkg + "." + className);
      } else {
        javaClassNames.add(pkg + "." + className);
      }
    }

    int idx = 0;
    for (int i = 0; i < fileCount; i++) {
      boolean isGroovy =
          i < groovyClassNames.size() + javaClassNames.size()
              && idx < groovyClassNames.size()
              && groovyClassNames.get(idx).contains("GClass" + i);

      // Re-derive from the pattern
      isGroovy = (new Random(42 + i).nextDouble() < 0.10);
      String className = (isGroovy ? "GClass" : "JClass") + i;
      String pkg = "bench.pkg" + (i % 10);
      String fqn = pkg + "." + className;

      // 20% chance of extending another workspace type
      String extendsClass = null;
      if (rng.nextDouble() < 0.20 && !javaClassNames.isEmpty()) {
        int pick = rng.nextInt(javaClassNames.size());
        String candidate = javaClassNames.get(pick);
        if (!candidate.equals(fqn)) {
          extendsClass = candidate;
        }
      }

      int methodCount = 3 + rng.nextInt(3); // 3-5
      int fieldCount = 2 + rng.nextInt(3); // 2-4

      String content;
      String ext;
      if (isGroovy) {
        content = generateGroovy(pkg, className, extendsClass, methodCount, fieldCount, rng);
        ext = ".groovy";
      } else {
        content = generateJava(pkg, className, extendsClass, methodCount, fieldCount, rng);
        ext = ".java";
      }

      String uri = "file:///bench/" + pkg.replace('.', '/') + "/" + className + ext;
      files.add(new SourceFile(uri, content, isGroovy ? "groovy" : "java"));
    }
    return files;
  }

  private static String generateJava(
      String pkg,
      String className,
      String extendsClass,
      int methodCount,
      int fieldCount,
      Random rng) {
    StringBuilder sb = new StringBuilder();
    sb.append("package ").append(pkg).append(";\n\n");

    if (extendsClass != null) {
      sb.append("import ").append(extendsClass).append(";\n");
    }
    sb.append("import java.util.List;\n\n");

    sb.append("public class ").append(className);
    if (extendsClass != null) {
      String simpleName = extendsClass.substring(extendsClass.lastIndexOf('.') + 1);
      sb.append(" extends ").append(simpleName);
    }
    sb.append(" {\n\n");

    String[] types = {"int", "String", "boolean", "double"};
    for (int f = 0; f < fieldCount; f++) {
      String type = types[rng.nextInt(types.length)];
      sb.append("  private ").append(type).append(" field").append(f).append(";\n");
    }
    sb.append("\n");

    for (int m = 0; m < methodCount; m++) {
      String retType = types[rng.nextInt(types.length)];
      sb.append("  public ").append(retType).append(" method").append(m).append("(");
      sb.append(types[rng.nextInt(types.length)]).append(" param0");
      sb.append(") {\n");
      sb.append("    // body\n");
      if ("int".equals(retType)) sb.append("    return 0;\n");
      else if ("boolean".equals(retType)) sb.append("    return false;\n");
      else if ("double".equals(retType)) sb.append("    return 0.0;\n");
      else sb.append("    return null;\n");
      sb.append("  }\n\n");
    }

    sb.append("}\n");
    return sb.toString();
  }

  private static String generateGroovy(
      String pkg,
      String className,
      String extendsClass,
      int methodCount,
      int fieldCount,
      Random rng) {
    StringBuilder sb = new StringBuilder();
    sb.append("package ").append(pkg).append("\n\n");

    if (extendsClass != null) {
      sb.append("import ").append(extendsClass).append("\n");
    }
    sb.append("\n");

    sb.append("class ").append(className);
    if (extendsClass != null) {
      String simpleName = extendsClass.substring(extendsClass.lastIndexOf('.') + 1);
      sb.append(" extends ").append(simpleName);
    }
    sb.append(" {\n\n");

    String[] types = {"int", "String", "boolean", "def"};
    for (int f = 0; f < fieldCount; f++) {
      String type = types[rng.nextInt(types.length)];
      sb.append("  ").append(type).append(" field").append(f).append("\n");
    }
    sb.append("\n");

    for (int m = 0; m < methodCount; m++) {
      sb.append("  def method").append(m).append("(param0) {\n");
      sb.append("    // body\n");
      sb.append("    return null\n");
      sb.append("  }\n\n");
    }

    sb.append("}\n");
    return sb.toString();
  }
}
