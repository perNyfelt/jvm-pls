package test.alipsa.jvmpls.groovy.dynamic;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import se.alipsa.jvmpls.core.CoreQuery;
import se.alipsa.jvmpls.core.model.InferenceConfidence;
import se.alipsa.jvmpls.core.model.Position;
import se.alipsa.jvmpls.core.model.Range;
import se.alipsa.jvmpls.core.model.SymbolInfo;
import se.alipsa.jvmpls.core.model.SyntheticOrigin;
import se.alipsa.jvmpls.core.types.JvmTypes;
import se.alipsa.jvmpls.core.types.MethodSignature;
import se.alipsa.jvmpls.core.types.VoidType;
import se.alipsa.jvmpls.groovy.dynamic.GroovyMemberResolver;
import se.alipsa.jvmpls.groovy.dynamic.ScopedSyntheticMember;

class GroovyMemberResolverTest {

  @Test
  void prefers_real_members_then_deterministic_then_high_then_low_and_avoids_cycles() {
    MethodSignature emptySig =
        new MethodSignature(
            List.of(), VoidType.INSTANCE, List.of(), List.of(), List.of(), Set.of("public"));
    SymbolInfo real =
        method(
            "demo.Owner",
            "alpha",
            emptySig,
            SyntheticOrigin.NONE,
            InferenceConfidence.DETERMINISTIC);
    SymbolInfo inheritedLow =
        method("demo.Base", "gamma", emptySig, SyntheticOrigin.MIXIN, InferenceConfidence.LOW);
    SymbolInfo deterministicScoped =
        method(
            "demo.Owner",
            "beta",
            emptySig,
            SyntheticOrigin.DELEGATE,
            InferenceConfidence.DETERMINISTIC);
    SymbolInfo highScopedDuplicate =
        method("demo.Owner", "beta", emptySig, SyntheticOrigin.CATEGORY, InferenceConfidence.HIGH);

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
            return switch (ownerFqn) {
              case "demo.Owner" -> List.of(real);
              case "demo.Base" -> List.of(inheritedLow);
              default -> List.of();
            };
          }

          @Override
          public List<String> supertypesOf(String typeFqn) {
            return switch (typeFqn) {
              case "demo.Owner" -> List.of("demo.Base");
              case "demo.Base" -> List.of("demo.Owner");
              default -> List.of();
            };
          }
        };

    GroovyMemberResolver resolver =
        new GroovyMemberResolver(
            core,
            (fileUri, position) ->
                List.of(
                    new ScopedSyntheticMember(
                        "demo.Owner",
                        new Range(new Position(0, 0), new Position(10, 0)),
                        deterministicScoped),
                    new ScopedSyntheticMember(
                        "demo.Owner",
                        new Range(new Position(0, 0), new Position(10, 0)),
                        highScopedDuplicate)),
            core::supertypesOf,
            ignored -> false,
            ignored -> false);

    List<String> names =
        resolver.membersAt("file:///Main.groovy", new Position(5, 0), "demo.Owner").stream()
            .map(SymbolInfo::getFqName)
            .toList();

    assertEquals(
        List.of(real.getFqName(), deterministicScoped.getFqName(), inheritedLow.getFqName()),
        names,
        "Resolver should order real members first, dedupe by callable identity, and avoid supertype"
            + " cycles");
  }

  private static SymbolInfo method(
      String ownerFqn,
      String methodName,
      MethodSignature signature,
      SyntheticOrigin origin,
      InferenceConfidence confidence) {
    return new SymbolInfo(
        "groovy",
        SymbolInfo.Kind.METHOD,
        ownerFqn + "#" + methodName + JvmTypes.toLegacyMethodSignature(signature),
        ownerFqn,
        null,
        JvmTypes.toLegacyMethodSignature(signature),
        signature.modifiers(),
        List.of(),
        null,
        signature,
        origin,
        confidence);
  }
}
