package test.alipsa.jvmpls.core;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import se.alipsa.jvmpls.core.StructuralHash;

class StructuralHashTest {

  @Test
  void bodyOnlyChangeProducesSameHash() {
    String source1 =
        """
        package demo;
        public class Foo {
          public int compute(int x) {
            return x + 1;
          }
        }
        """;
    String source2 =
        """
        package demo;
        public class Foo {
          public int compute(int x) {
            return x * 2 + 1;
          }
        }
        """;
    assertEquals(StructuralHash.compute(source1), StructuralHash.compute(source2));
  }

  @Test
  void methodSignatureChangeProducesDifferentHash() {
    String source1 =
        """
        package demo;
        public class Foo {
          public int compute(int x) {
            return x;
          }
        }
        """;
    String source2 =
        """
        package demo;
        public class Foo {
          public int compute(int x, int y) {
            return x;
          }
        }
        """;
    assertNotEquals(StructuralHash.compute(source1), StructuralHash.compute(source2));
  }

  @Test
  void addedFieldChangeProducesDifferentHash() {
    String source1 =
        """
        package demo;
        public class Foo {
          private int x;
        }
        """;
    String source2 =
        """
        package demo;
        public class Foo {
          private int x;
          private String name;
        }
        """;
    assertNotEquals(StructuralHash.compute(source1), StructuralHash.compute(source2));
  }

  @Test
  void importChangeProducesDifferentHash() {
    String source1 =
        """
        package demo;
        import java.util.List;
        public class Foo {}
        """;
    String source2 =
        """
        package demo;
        import java.util.List;
        import java.util.Map;
        public class Foo {}
        """;
    assertNotEquals(StructuralHash.compute(source1), StructuralHash.compute(source2));
  }

  @Test
  void commentOnlyChangeProducesSameHash() {
    String source1 =
        """
        package demo;
        public class Foo {
          public void run() {}
        }
        """;
    String source2 =
        """
        package demo;
        // Added a comment
        public class Foo {
          /** method javadoc */
          public void run() {}
        }
        """;
    assertEquals(StructuralHash.compute(source1), StructuralHash.compute(source2));
  }

  @Test
  void groovyFieldWithoutModifiersDetected() {
    String source1 =
        """
        package demo
        class Foo {
          String name;
        }
        """;
    String source2 =
        """
        package demo
        class Foo {
          String name;
          int age;
        }
        """;
    assertNotEquals(StructuralHash.compute(source1), StructuralHash.compute(source2));
  }

  @Test
  void localVariableChangesInsideMethodDoNotAffectHash() {
    String source1 =
        """
        package demo;
        class Foo {
          String name;
          void run() {
            int value = 1;
          }
        }
        """;
    String source2 =
        """
        package demo;
        class Foo {
          String name;
          void run() {
            int renamed = 2;
          }
        }
        """;
    assertEquals(StructuralHash.compute(source1), StructuralHash.compute(source2));
  }
}
