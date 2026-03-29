package se.alipsa.jvmpls.core.types;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class JvmTypes {

  private JvmTypes() {}

  public static JvmType fromSource(String rawType, Function<String, String> classNameResolver) {
    if (rawType == null || rawType.isBlank()) {
      return DynamicType.INSTANCE;
    }
    String value = rawType.trim();
    if ("def".equals(value)) {
      return DynamicType.INSTANCE;
    }
    if (value.endsWith("[]")) {
      return new ArrayType(fromSource(value.substring(0, value.length() - 2), classNameResolver));
    }
    if ("void".equals(value)) {
      return VoidType.INSTANCE;
    }
    if (isPrimitive(value)) {
      return new PrimitiveType(value);
    }
    if ("?".equals(value)) {
      return new WildcardType(WildcardType.Variance.UNBOUNDED, null);
    }
    if (value.startsWith("? extends ")) {
      return new WildcardType(
          WildcardType.Variance.EXTENDS,
          fromSource(value.substring("? extends ".length()), classNameResolver));
    }
    if (value.startsWith("? super ")) {
      return new WildcardType(
          WildcardType.Variance.SUPER,
          fromSource(value.substring("? super ".length()), classNameResolver));
    }

    int genericStart = indexOfTopLevel(value, '<');
    if (genericStart >= 0 && value.endsWith(">")) {
      String base = value.substring(0, genericStart).trim();
      String rawArgs = value.substring(genericStart + 1, value.length() - 1);
      return new ClassType(
          resolveClassName(base, classNameResolver),
          splitTopLevel(rawArgs).stream().map(arg -> fromSource(arg, classNameResolver)).toList());
    }

    if (looksLikeTypeVariable(value)) {
      return new TypeVariable(value, List.of());
    }
    return new ClassType(resolveClassName(value, classNameResolver), List.of());
  }

  public static JvmType fromDescriptor(String descriptor) {
    if (descriptor == null || descriptor.isBlank()) {
      return DynamicType.INSTANCE;
    }
    DescriptorCursor cursor = new DescriptorCursor(descriptor);
    return parseDescriptorType(cursor);
  }

  public static MethodSignature fromLegacyMethodSignature(
      String legacySignature, Set<String> modifiers) {
    if (legacySignature == null || legacySignature.isBlank()) {
      return new MethodSignature(
          List.of(), VoidType.INSTANCE, List.of(), List.of(), List.of(), modifiers);
    }
    int open = legacySignature.indexOf('(');
    int close = legacySignature.indexOf(')', open + 1);
    if (open < 0 || close < 0) {
      return new MethodSignature(
          List.of(), DynamicType.INSTANCE, List.of(), List.of(), List.of(), modifiers);
    }
    String rawParams = legacySignature.substring(open + 1, close).trim();
    String rawReturn = legacySignature.substring(close + 1).trim();
    List<JvmType> params =
        rawParams.isEmpty()
            ? List.of()
            : splitTopLevel(rawParams).stream()
                .map(arg -> fromSource(arg, Function.identity()))
                .toList();
    JvmType returnType =
        rawReturn.isEmpty() ? VoidType.INSTANCE : fromSource(rawReturn, Function.identity());
    return new MethodSignature(params, returnType, List.of(), List.of(), List.of(), modifiers);
  }

  public static MethodSignature fromMethodDescriptor(
      String descriptor,
      List<String> parameterNames,
      List<String> throwsTypeNames,
      Set<String> modifiers) {
    if (descriptor == null || descriptor.isBlank() || descriptor.charAt(0) != '(') {
      return new MethodSignature(
          List.of(), DynamicType.INSTANCE, parameterNames, List.of(), List.of(), modifiers);
    }
    DescriptorCursor cursor = new DescriptorCursor(descriptor);
    cursor.expect('(');
    List<JvmType> params = new ArrayList<>();
    while (!cursor.isAt(')') && !cursor.isDone()) {
      params.add(parseDescriptorType(cursor));
    }
    cursor.expect(')');
    JvmType returnType = parseDescriptorType(cursor);
    List<JvmType> throwsTypes =
        throwsTypeNames == null
            ? List.of()
            : throwsTypeNames.stream()
                .<JvmType>map(name -> new ClassType(name, List.of()))
                .toList();
    return new MethodSignature(
        params, returnType, parameterNames, List.of(), throwsTypes, modifiers);
  }

  public static String toLegacyMethodSignature(MethodSignature signature) {
    return "("
        + signature.parameterTypes().stream()
            .map(JvmType::displayName)
            .collect(Collectors.joining(","))
        + ")"
        + signature.returnType().displayName();
  }

  public static String simpleName(String fqName) {
    int lastDot = fqName.lastIndexOf('.');
    return lastDot < 0 ? fqName : fqName.substring(lastDot + 1);
  }

  public static boolean isPrimitive(String value) {
    return switch (value) {
      case "boolean", "byte", "char", "short", "int", "long", "float", "double" -> true;
      default -> false;
    };
  }

  private static boolean looksLikeTypeVariable(String value) {
    return value.length() == 1 && Character.isUpperCase(value.charAt(0));
  }

  private static String resolveClassName(
      String rawName, Function<String, String> classNameResolver) {
    if (rawName.contains(".")) {
      return rawName;
    }
    if (classNameResolver == null) {
      return rawName;
    }
    String resolved = classNameResolver.apply(rawName);
    return resolved == null || resolved.isBlank() ? rawName : resolved;
  }

  public static List<String> splitTopLevel(String raw) {
    List<String> out = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    int depth = 0;
    for (int i = 0; i < raw.length(); i++) {
      char ch = raw.charAt(i);
      if (ch == '<') {
        depth++;
      } else if (ch == '>') {
        depth = Math.max(depth - 1, 0);
      } else if (ch == ',' && depth == 0) {
        out.add(current.toString().trim());
        current.setLength(0);
        continue;
      }
      current.append(ch);
    }
    if (!current.isEmpty()) {
      out.add(current.toString().trim());
    }
    return out;
  }

  private static int indexOfTopLevel(String value, char needle) {
    int depth = 0;
    for (int i = 0; i < value.length(); i++) {
      char ch = value.charAt(i);
      if (ch == '<') depth++;
      if (ch == '>') depth = Math.max(depth - 1, 0);
      if (ch == needle && depth == 1) {
        return i;
      }
    }
    return -1;
  }

  private static JvmType parseDescriptorType(DescriptorCursor cursor) {
    if (cursor.isDone()) {
      return DynamicType.INSTANCE;
    }
    return switch (cursor.next()) {
      case 'V' -> VoidType.INSTANCE;
      case 'Z' -> new PrimitiveType("boolean");
      case 'C' -> new PrimitiveType("char");
      case 'B' -> new PrimitiveType("byte");
      case 'S' -> new PrimitiveType("short");
      case 'I' -> new PrimitiveType("int");
      case 'F' -> new PrimitiveType("float");
      case 'J' -> new PrimitiveType("long");
      case 'D' -> new PrimitiveType("double");
      case '[' -> new ArrayType(parseDescriptorType(cursor));
      case 'L' -> new ClassType(readObjectDescriptor(cursor), List.of());
      case 'T' -> new TypeVariable(readUntil(cursor, ';'), List.of());
      default -> DynamicType.INSTANCE;
    };
  }

  private static String readObjectDescriptor(DescriptorCursor cursor) {
    String raw = readUntil(cursor, ';');
    int genericStart = raw.indexOf('<');
    if (genericStart >= 0) {
      raw = raw.substring(0, genericStart);
    }
    return raw.replace('/', '.');
  }

  private static String readUntil(DescriptorCursor cursor, char terminal) {
    StringBuilder value = new StringBuilder();
    while (!cursor.isDone() && !cursor.isAt(terminal)) {
      value.append(cursor.next());
    }
    cursor.expect(terminal);
    return value.toString();
  }

  private static final class DescriptorCursor {
    private final String value;
    private int index;

    private DescriptorCursor(String value) {
      this.value = value;
    }

    private boolean isDone() {
      return index >= value.length();
    }

    private boolean isAt(char ch) {
      return !isDone() && value.charAt(index) == ch;
    }

    private char next() {
      return value.charAt(index++);
    }

    private void expect(char ch) {
      if (isAt(ch)) {
        index++;
      }
    }
  }
}
