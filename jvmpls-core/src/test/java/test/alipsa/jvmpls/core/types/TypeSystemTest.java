package test.alipsa.jvmpls.core.types;

import org.junit.jupiter.api.Test;
import se.alipsa.jvmpls.core.CoreQuery;
import se.alipsa.jvmpls.core.model.SymbolInfo;
import se.alipsa.jvmpls.core.types.ArrayType;
import se.alipsa.jvmpls.core.types.ClassType;
import se.alipsa.jvmpls.core.types.DynamicType;
import se.alipsa.jvmpls.core.types.JvmType;
import se.alipsa.jvmpls.core.types.JvmTypes;
import se.alipsa.jvmpls.core.types.MethodSignature;
import se.alipsa.jvmpls.core.types.TypeRelations;
import se.alipsa.jvmpls.core.types.TypeResolver;
import se.alipsa.jvmpls.core.types.WildcardType;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TypeSystemTest {

  @Test
  void parses_source_and_descriptor_types() {
    JvmType source = JvmTypes.fromSource("List<String>[]", simpleName -> switch (simpleName) {
      case "List" -> "java.util.List";
      case "String" -> "java.lang.String";
      default -> simpleName;
    });

    assertInstanceOf(ArrayType.class, source);
    assertEquals("java.util.List<java.lang.String>[]", source.displayName());
    assertEquals("java.lang.String[]", JvmTypes.fromDescriptor("[Ljava/lang/String;").displayName());

    MethodSignature method = JvmTypes.fromMethodDescriptor(
        "(Ljava/lang/String;I)Ljava/util/List;",
        List.of("value", "count"),
        List.of("java.io.IOException"),
        java.util.Set.of("public"));
    assertEquals(List.of("java.lang.String", "int"),
        method.parameterTypes().stream().map(JvmType::displayName).toList());
    assertEquals("java.util.List", method.returnType().displayName());
    assertEquals(List.of("value", "count"), method.parameterNames());
    assertEquals(List.of("java.io.IOException"),
        method.throwsTypes().stream().map(JvmType::displayName).toList());
  }

  @Test
  void typeResolver_verifies_star_import_candidates_against_core() {
    CoreQuery core = new CoreQuery() {
      @Override
      public Optional<SymbolInfo> findByFqn(String fqn) {
        return switch (fqn) {
          case "java.util.List", "java.lang.String", "demo.Widget" ->
              Optional.of(new SymbolInfo("test", SymbolInfo.Kind.CLASS, fqn, "", null, "", java.util.Set.of(), List.of()));
          default -> Optional.empty();
        };
      }

      @Override
      public List<SymbolInfo> findBySimpleName(String simpleName) {
        return List.of();
      }

      @Override
      public List<SymbolInfo> allInPackage(String pkg) {
        return List.of();
      }
    };

    TypeResolver resolver = new TypeResolver(core);

    assertEquals("java.util.List",
        resolver.resolveClassName("List", "demo", List.of("groovy.io.*", "java.util.*")));
    assertEquals("demo.Widget",
        resolver.resolveClassName("Widget", "demo", List.of("java.util.*")));
    assertEquals("java.lang.String",
        resolver.resolveClassName("String", "demo", List.of("java.util.*")));
  }

  @Test
  void typeRelations_use_structural_equality_and_dynamic_shortcuts() {
    TypeRelations relations = new TypeRelations();
    JvmType list = new ClassType("java.util.List", List.of());

    assertTrue(relations.isAssignableTo(list, new ClassType("java.util.List", List.of())));
    assertTrue(relations.isAssignableTo(new ArrayType(list), new ClassType("java.lang.Object", List.of())));
    assertTrue(relations.isAssignableTo(DynamicType.INSTANCE, list));
    assertEquals(list, relations.commonSupertype(list, new ClassType("java.util.List", List.of())));
  }

  @Test
  void wildcard_requires_bound_for_bounded_variance() {
    assertThrows(IllegalArgumentException.class,
        () -> new WildcardType(WildcardType.Variance.EXTENDS, null));
    assertThrows(IllegalArgumentException.class,
        () -> new WildcardType(WildcardType.Variance.SUPER, null));
    assertEquals("?", new WildcardType(WildcardType.Variance.UNBOUNDED, null).displayName());
  }
}
