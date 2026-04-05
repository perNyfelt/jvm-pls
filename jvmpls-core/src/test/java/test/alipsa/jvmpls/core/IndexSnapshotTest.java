package test.alipsa.jvmpls.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import se.alipsa.jvmpls.core.IndexSnapshot;
import se.alipsa.jvmpls.core.SymbolIndex;
import se.alipsa.jvmpls.core.model.Location;
import se.alipsa.jvmpls.core.model.Position;
import se.alipsa.jvmpls.core.model.Range;
import se.alipsa.jvmpls.core.model.SymbolInfo;

class IndexSnapshotTest {

  @TempDir Path tempDir;

  @Test
  void loadRestoresTypedFieldAndMethodMetadataFromLegacySignatures() {
    SymbolIndex source = new SymbolIndex();
    String uri = "file:///demo/Foo.java";
    Location location = new Location(uri, new Range(new Position(1, 0), new Position(1, 10)));
    source.put(
        uri,
        new SymbolInfo(
            "java",
            SymbolInfo.Kind.FIELD,
            "demo.Foo.name",
            "demo.Foo",
            location,
            "java.lang.String",
            Set.of("private"),
            List.of()));
    source.put(
        uri,
        new SymbolInfo(
            "java",
            SymbolInfo.Kind.METHOD,
            "demo.Foo#size()int",
            "demo.Foo",
            location,
            "()int",
            Set.of("public"),
            List.of()));

    IndexSnapshot snapshot = new IndexSnapshot(tempDir);
    snapshot.save(source);

    SymbolIndex restored = new SymbolIndex();
    snapshot.load(restored);

    SymbolInfo field = restored.findByFqn("demo.Foo.name").orElseThrow();
    assertNotNull(field.getResolvedType());
    assertEquals("java.lang.String", field.getResolvedType().displayName());

    SymbolInfo method = restored.findByFqn("demo.Foo#size()int").orElseThrow();
    assertNotNull(method.getMethodSignature());
    assertEquals("int", method.getMethodSignature().returnType().displayName());
    assertEquals("public", method.getMethodSignature().modifiers().iterator().next());
  }
}
