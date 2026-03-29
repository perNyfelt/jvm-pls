package test.alipsa.jvmpls.groovy.dynamic;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import se.alipsa.jvmpls.core.model.InferenceConfidence;
import se.alipsa.jvmpls.core.model.Position;
import se.alipsa.jvmpls.core.model.Range;
import se.alipsa.jvmpls.core.model.SymbolInfo;
import se.alipsa.jvmpls.core.model.SyntheticOrigin;
import se.alipsa.jvmpls.groovy.dynamic.ScopedSyntheticMember;

class ScopedSyntheticMemberTest {

  @Test
  void is_visible_at_includes_exact_start_and_end_boundaries() {
    ScopedSyntheticMember member =
        new ScopedSyntheticMember(
            "demo.StringCategory",
            new Range(new Position(3, 4), new Position(8, 12)),
            new SymbolInfo(
                "groovy",
                SymbolInfo.Kind.METHOD,
                "demo.StringCategory#shout()java.lang.String",
                "demo.StringCategory",
                null,
                "()java.lang.String",
                java.util.Set.of("public"),
                java.util.List.of(),
                null,
                null,
                SyntheticOrigin.CATEGORY,
                InferenceConfidence.HIGH));

    assertTrue(member.isVisibleAt(new Position(3, 4)));
    assertTrue(member.isVisibleAt(new Position(8, 12)));
    assertFalse(member.isVisibleAt(new Position(3, 3)));
    assertFalse(member.isVisibleAt(new Position(8, 13)));
  }
}
