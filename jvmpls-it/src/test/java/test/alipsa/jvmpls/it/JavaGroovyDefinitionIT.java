package test.alipsa.jvmpls.it;

import org.junit.jupiter.api.Test;
import se.alipsa.jvmpls.core.model.Location;
import se.alipsa.jvmpls.core.model.Position;
import se.alipsa.jvmpls.core.server.CoreServer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class JavaGroovyDefinitionIT {

  @Test
  void java_to_groovy_and_back() throws Exception {
    Path dir = Files.createTempDirectory("jvmpls-it");

    Path groovy = dir.resolve("World.groovy");
    String groovyCode = """
      package demo
      class World {
        String greet() { 'hi' }
      }
      """;
    Files.writeString(groovy, groovyCode, StandardCharsets.UTF_8);
    String groovyUri = groovy.toUri().toString();

    Path java = dir.resolve("Main.java");
    String javaCode = """
      package demo;
      public class Main {
        public static void main(String[] args) {
          World w = new World();
          System.out.println(w.greet());
        }
      }
      """;
    Files.writeString(java, javaCode, StandardCharsets.UTF_8);
    String javaUri = java.toUri().toString();

    Path helloJava = dir.resolve("Hello.java");
    String helloJavaCode = """
      package demo;
      public class Hello { public static String msg() { return "hello"; } }
      """;
    Files.writeString(helloJava, helloJavaCode, StandardCharsets.UTF_8);
    String helloJavaUri = helloJava.toUri().toString();

    Path useGroovy = dir.resolve("UseHello.groovy");
    String useGroovyCode = """
      package demo
      class UseHello {
        static void run() {
          println Hello.msg()
        }
      }
      """;
    Files.writeString(useGroovy, useGroovyCode, StandardCharsets.UTF_8);
    String useGroovyUri = useGroovy.toUri().toString();

    try (CoreServer server = CoreServer.createDefault((u, d) -> {})) {
      server.openFile(groovyUri, groovyCode);
      server.openFile(javaUri,   javaCode);
      server.openFile(helloJavaUri, helloJavaCode);
      server.openFile(useGroovyUri, useGroovyCode);

      // Java -> Groovy
      Position posJavaWorld   = firstWholeWord(javaCode, "World");
      Optional<Location> def1 = server.definition(javaUri, posJavaWorld);
      assertTrue(def1.isPresent(), "Java->Groovy definition should be found");
      assertEquals(groovyUri, def1.get().getUri());

      // Groovy -> Java
      Position posGroovyHello = firstWholeWord(useGroovyCode, "Hello");
      Optional<Location> def2 = server.definition(useGroovyUri, posGroovyHello);
      assertTrue(def2.isPresent(), "Groovy->Java definition should be found");
      assertEquals(helloJavaUri, def2.get().getUri());
    }
  }

  /** Find the first whole-word occurrence and convert to a Position. */
  private static Position firstWholeWord(String text, String word) {
    var p = java.util.regex.Pattern.compile("\\b" + java.util.regex.Pattern.quote(word) + "\\b");
    var m = p.matcher(text);
    if (!m.find()) throw new AssertionError("word not found: " + word);
    int idx = m.start();
    int line = 0, col = 0;
    for (int i = 0; i < idx; i++) {
      char c = text.charAt(i);
      if (c == '\n') { line++; col = 0; } else { col++; }
    }
    return new Position(line, col);
  }
}
