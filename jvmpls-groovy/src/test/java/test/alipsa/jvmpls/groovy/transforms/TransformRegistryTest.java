package test.alipsa.jvmpls.groovy.transforms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.ModuleNode;
import org.codehaus.groovy.ast.builder.AstBuilder;
import org.codehaus.groovy.control.CompilePhase;
import org.junit.jupiter.api.Test;

import se.alipsa.jvmpls.core.CoreQuery;
import se.alipsa.jvmpls.core.model.Location;
import se.alipsa.jvmpls.core.model.Position;
import se.alipsa.jvmpls.core.model.Range;
import se.alipsa.jvmpls.core.model.SymbolInfo;
import se.alipsa.jvmpls.core.types.ClassType;
import se.alipsa.jvmpls.core.types.JvmTypes;
import se.alipsa.jvmpls.core.types.MethodSignature;
import se.alipsa.jvmpls.core.types.VoidType;
import se.alipsa.jvmpls.groovy.SyntheticMemberSpec;
import se.alipsa.jvmpls.groovy.transforms.TransformContext;
import se.alipsa.jvmpls.groovy.transforms.TransformRegistry;

class TransformRegistryTest {

  @Test
  void delegate_only_exposes_members_visible_from_the_owner_package() {
    TransformRegistry registry = new TransformRegistry();
    ClassNode crossPackageOwner =
        parsePrimaryClass(
            """
            package client
            class Owner {
              @groovy.lang.Delegate lib.Api api
            }
            """);
    ClassNode samePackageOwner =
        parsePrimaryClass(
            """
            package lib
            class Owner {
              @groovy.lang.Delegate lib.Api api
            }
            """);

    CoreQuery core =
        new CoreQuery() {
          @Override
          public Optional<SymbolInfo> findByFqn(String fqn) {
            return Optional.empty();
          }

          @Override
          public List<SymbolInfo> findBySimpleName(String simpleName) {
            return List.of();
          }

          @Override
          public List<SymbolInfo> allInPackage(String pkgFqn) {
            return List.of();
          }

          @Override
          public List<SymbolInfo> membersOf(String ownerFqn) {
            if (!"lib.Api".equals(ownerFqn)) {
              return List.of();
            }
            return List.of(
                method(ownerFqn, "pub", Set.of("public")),
                method(ownerFqn, "prot", Set.of("protected")),
                method(ownerFqn, "pkg", Set.of("package-private")),
                method(ownerFqn, "priv", Set.of("private")),
                method(ownerFqn, "util", Set.of("public", "static")));
          }
        };

    List<String> crossPackageMembers =
        memberNames(
            registry.analyzeClass(
                crossPackageOwner, contextFor("file:///Owner.groovy", "client.Owner", core)));
    List<String> samePackageMembers =
        memberNames(
            registry.analyzeClass(
                samePackageOwner, contextFor("file:///Owner.groovy", "lib.Owner", core)));

    assertEquals(
        List.of("pub"),
        crossPackageMembers,
        "Delegate should not leak package-private, protected, private, or static members across"
            + " packages");
    assertEquals(
        List.of("pub", "prot", "pkg"),
        samePackageMembers,
        "Delegate should keep same-package protected and package-private members");
  }

  @Test
  void constructor_transforms_do_not_duplicate_each_other_or_explicit_constructors() {
    TransformRegistry registry = new TransformRegistry();

    ClassNode duplicated =
        parsePrimaryClass(
            """
            @groovy.transform.TupleConstructor
            @groovy.transform.Canonical
            class Person {
              String name
            }
            """);
    ClassNode explicit =
        parsePrimaryClass(
            """
            @groovy.transform.TupleConstructor
            class Person {
              String name
              Person(String name) {
                this.name = name
              }
            }
            """);

    long duplicatedCount =
        registry
            .analyzeClass(duplicated, contextFor("file:///Person.groovy", "Person", emptyCore()))
            .stream()
            .filter(spec -> spec.kind() == SymbolInfo.Kind.CONSTRUCTOR)
            .count();
    long explicitCount =
        registry
            .analyzeClass(explicit, contextFor("file:///Person.groovy", "Person", emptyCore()))
            .stream()
            .filter(spec -> spec.kind() == SymbolInfo.Kind.CONSTRUCTOR)
            .count();

    assertEquals(
        1,
        duplicatedCount,
        "Overlapping constructor transforms should collapse to one synthetic constructor");
    assertEquals(
        0,
        explicitCount,
        "Synthetic constructors should not replace an explicit matching constructor");
  }

  @Test
  void analyze_class_requires_non_null_inputs() {
    TransformRegistry registry = new TransformRegistry();
    ClassNode classNode = parsePrimaryClass("class Person {}");
    TransformContext context = contextFor("file:///Person.groovy", "Person", emptyCore());

    assertThrows(NullPointerException.class, () -> registry.analyzeClass(null, context));
    assertThrows(NullPointerException.class, () -> registry.analyzeClass(classNode, null));
  }

  @Test
  void builder_and_log_transforms_emit_expected_synthetic_members() {
    TransformRegistry registry = new TransformRegistry();
    ClassNode classNode =
        parsePrimaryClass(
            """
            @groovy.transform.builder.Builder
            @groovy.util.logging.Log
            class Person {
              String name
            }
            """);

    List<SyntheticMemberSpec> specs =
        registry.analyzeClass(
            classNode, contextFor("file:///Person.groovy", "Person", emptyCore()));

    assertTrue(
        specs.stream()
            .anyMatch(
                spec ->
                    spec.kind() == SymbolInfo.Kind.CLASS
                        && "PersonBuilder".equals(spec.ownerFqn())));
    assertTrue(
        specs.stream()
            .anyMatch(
                spec ->
                    spec.kind() == SymbolInfo.Kind.METHOD && "builder".equals(spec.memberName())));
    assertTrue(
        specs.stream()
            .anyMatch(
                spec -> spec.kind() == SymbolInfo.Kind.FIELD && "log".equals(spec.memberName())));
  }

  private static TransformContext contextFor(String fileUri, String ownerFqn, CoreQuery core) {
    return new TransformContext(
        fileUri,
        ownerFqn,
        core,
        type -> new ClassType(type.getName(), List.of()),
        node -> new Location(fileUri, new Range(new Position(0, 0), new Position(0, 1))));
  }

  private static CoreQuery emptyCore() {
    return new CoreQuery() {
      @Override
      public Optional<SymbolInfo> findByFqn(String fqn) {
        return Optional.empty();
      }

      @Override
      public List<SymbolInfo> findBySimpleName(String simpleName) {
        return List.of();
      }

      @Override
      public List<SymbolInfo> allInPackage(String pkgFqn) {
        return List.of();
      }
    };
  }

  private static SymbolInfo method(String ownerFqn, String name, Set<String> modifiers) {
    MethodSignature signature =
        new MethodSignature(
            List.of(), VoidType.INSTANCE, List.of(), List.of(), List.of(), modifiers);
    return new SymbolInfo(
        "java",
        SymbolInfo.Kind.METHOD,
        ownerFqn + "#" + name + JvmTypes.toLegacyMethodSignature(signature),
        ownerFqn,
        null,
        JvmTypes.toLegacyMethodSignature(signature),
        modifiers,
        List.of(),
        null,
        signature);
  }

  private static List<String> memberNames(List<SyntheticMemberSpec> specs) {
    ArrayList<String> names = new ArrayList<>();
    for (SyntheticMemberSpec spec : specs) {
      if (spec.kind() == SymbolInfo.Kind.METHOD) {
        names.add(spec.memberName());
      }
    }
    return names;
  }

  private static ClassNode parsePrimaryClass(String source) {
    List<ASTNode> nodes = new AstBuilder().buildFromString(CompilePhase.CONVERSION, false, source);
    for (ASTNode node : nodes) {
      if (node instanceof ModuleNode moduleNode && !moduleNode.getClasses().isEmpty()) {
        return moduleNode.getClasses().getFirst();
      }
      if (node instanceof ClassNode classNode) {
        return classNode;
      }
    }
    throw new AssertionError("No class node found");
  }
}
