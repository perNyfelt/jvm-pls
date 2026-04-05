package test.alipsa.jvmpls.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import se.alipsa.jvmpls.core.ApiFingerprint;
import se.alipsa.jvmpls.core.SymbolIndex;
import se.alipsa.jvmpls.core.model.Location;
import se.alipsa.jvmpls.core.model.Position;
import se.alipsa.jvmpls.core.model.Range;
import se.alipsa.jvmpls.core.model.SymbolInfo;

class ApiFingerprintTest {

  private static final String FILE_URI = "file:///demo/Foo.java";
  private static final Location LOC =
      new Location(FILE_URI, new Range(new Position(0, 0), new Position(0, 1)));

  @Test
  void emptyFileProducesEmptyFingerprint() {
    SymbolIndex index = new SymbolIndex();
    ApiFingerprint fp = ApiFingerprint.compute(FILE_URI, index);
    assertNotNull(fp);
  }

  @Test
  void sameDeclarationsProduceSameFingerprint() {
    SymbolIndex index1 = createIndexWithPublicMethod("demo.Foo#bar(int)", "(int)void");
    SymbolIndex index2 = createIndexWithPublicMethod("demo.Foo#bar(int)", "(int)void");

    ApiFingerprint fp1 = ApiFingerprint.compute(FILE_URI, index1);
    ApiFingerprint fp2 = ApiFingerprint.compute(FILE_URI, index2);
    assertEquals(fp1, fp2);
  }

  @Test
  void differentSignatureProducesDifferentFingerprint() {
    SymbolIndex index1 = createIndexWithPublicMethod("demo.Foo#bar(int)", "(int)void");
    SymbolIndex index2 = createIndexWithPublicMethod("demo.Foo#bar(int,int)", "(int,int)void");

    ApiFingerprint fp1 = ApiFingerprint.compute(FILE_URI, index1);
    ApiFingerprint fp2 = ApiFingerprint.compute(FILE_URI, index2);
    assertNotEquals(fp1, fp2);
  }

  @Test
  void privateMethodsExcluded() {
    SymbolIndex index1 = new SymbolIndex();
    index1.put(
        FILE_URI,
        new SymbolInfo(
            "java",
            SymbolInfo.Kind.CLASS,
            "demo.Foo",
            "demo",
            LOC,
            "",
            Set.of("public"),
            List.of()));

    SymbolIndex index2 = new SymbolIndex();
    index2.put(
        FILE_URI,
        new SymbolInfo(
            "java",
            SymbolInfo.Kind.CLASS,
            "demo.Foo",
            "demo",
            LOC,
            "",
            Set.of("public"),
            List.of()));
    index2.put(
        FILE_URI,
        new SymbolInfo(
            "java",
            SymbolInfo.Kind.METHOD,
            "demo.Foo#secret()",
            "demo.Foo",
            LOC,
            "()void",
            Set.of("private"),
            List.of()));

    ApiFingerprint fp1 = ApiFingerprint.compute(FILE_URI, index1);
    ApiFingerprint fp2 = ApiFingerprint.compute(FILE_URI, index2);
    assertEquals(fp1, fp2);
  }

  private SymbolIndex createIndexWithPublicMethod(String methodFqn, String signature) {
    SymbolIndex index = new SymbolIndex();
    index.put(
        FILE_URI,
        new SymbolInfo(
            "java",
            SymbolInfo.Kind.CLASS,
            "demo.Foo",
            "demo",
            LOC,
            "",
            Set.of("public"),
            List.of()));
    index.put(
        FILE_URI,
        new SymbolInfo(
            "java",
            SymbolInfo.Kind.METHOD,
            methodFqn,
            "demo.Foo",
            LOC,
            signature,
            Set.of("public"),
            List.of()));
    return index;
  }
}
