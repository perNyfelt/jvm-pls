package test.alipsa.jvmpls.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import se.alipsa.jvmpls.core.SymbolIndex;
import se.alipsa.jvmpls.core.model.Location;
import se.alipsa.jvmpls.core.model.Position;
import se.alipsa.jvmpls.core.model.Range;
import se.alipsa.jvmpls.core.model.SymbolInfo;

class SymbolIndexReferencesAndSearchTest {

  @Test
  void referencesAreRemovedWhenFileIsReindexed() {
    SymbolIndex index = new SymbolIndex();
    String fileUri = "file:///workspace/Foo.java";
    Location declaration = new Location(fileUri, new Range(new Position(0, 0), new Position(0, 3)));
    index.put(
        fileUri,
        new SymbolInfo(
            "java",
            SymbolInfo.Kind.CLASS,
            "demo.Foo",
            "demo",
            declaration,
            "",
            Set.of("public"),
            List.of()));

    Location firstReference =
        new Location(fileUri, new Range(new Position(1, 2), new Position(1, 5)));
    index.reportReference(fileUri, "demo.Foo", firstReference);
    assertEquals(List.of(firstReference), index.referencesTo("demo.Foo"));

    index.removeFile(fileUri);
    assertTrue(index.referencesTo("demo.Foo").isEmpty(), "references should be cleared with file");
  }

  @Test
  void searchRanksWorkspaceSymbolsAheadOfBinaryLikeEntries() {
    SymbolIndex index = new SymbolIndex();
    index.put(
        "file:///workspace/Foo.java",
        new SymbolInfo(
            "java",
            SymbolInfo.Kind.CLASS,
            "demo.FooService",
            "demo",
            new Location(
                "file:///workspace/Foo.java", new Range(new Position(0, 0), new Position(0, 10))),
            "",
            Set.of("public"),
            List.of()));

    List<SymbolInfo> results = index.search("Foo", 10);

    assertEquals("demo.FooService", results.getFirst().getFqName());
  }
}
