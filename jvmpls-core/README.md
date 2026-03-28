# jvmpls-core

`jvmpls-core` is the embedded, in-process API for `jvm-pls`.

Use this module when you want JVM language indexing, diagnostics, completions, and go-to-definition inside your own application without speaking LSP or launching the standalone server process.

## Dependencies

Add `jvmpls-core` plus the language plugins you want on the runtime classpath.

```xml
<dependencies>
  <dependency>
    <groupId>se.alipsa.jvmpls</groupId>
    <artifactId>jvmpls-core</artifactId>
    <version>1.0.0-SNAPSHOT</version>
  </dependency>

  <dependency>
    <groupId>se.alipsa.jvmpls</groupId>
    <artifactId>jvmpls-classpath</artifactId>
    <version>1.0.0-SNAPSHOT</version>
  </dependency>

  <dependency>
    <groupId>se.alipsa.jvmpls</groupId>
    <artifactId>jvmpls-java</artifactId>
    <version>1.0.0-SNAPSHOT</version>
  </dependency>

  <dependency>
    <groupId>se.alipsa.jvmpls</groupId>
    <artifactId>jvmpls-groovy</artifactId>
    <version>1.0.0-SNAPSHOT</version>
  </dependency>
</dependencies>
```

Notes:

- `jvmpls-core` provides the transport-agnostic API.
- `jvmpls-classpath` enables external JDK and dependency JAR resolution.
- `jvmpls-java` and `jvmpls-groovy` are discovered via `ServiceLoader`.
- If a plugin is not on the runtime classpath, files for that language will not be handled.

## Entry Point

Create a [`CoreServer`](src/main/java/se/alipsa/jvmpls/core/server/CoreServer.java), which implements [`CoreFacade`](src/main/java/se/alipsa/jvmpls/core/CoreFacade.java):

```java
try (CoreServer server = CoreServer.createDefault((uri, diagnostics) -> {
  System.out.println("Diagnostics for " + uri + ": " + diagnostics);
})) {
  // use server here
}
```

`createDefault(...)` builds an in-process engine with:

- plugin discovery via `ServiceLoader`
- symbol index and document store setup
- a diagnostics callback you provide

## Minimal Example

```java
import se.alipsa.jvmpls.core.model.Position;
import se.alipsa.jvmpls.core.server.CoreServer;

import java.nio.file.Files;
import java.nio.file.Path;

public class EmbeddedExample {
  public static void main(String[] args) throws Exception {
    Path file = Path.of("src/main/java/demo/Hello.java");
    String uri = file.toUri().toString();
    String text = Files.readString(file);

    try (CoreServer server = CoreServer.createDefault((diagnosticUri, diagnostics) -> {
      System.out.println("Diagnostics for " + diagnosticUri + ": " + diagnostics);
    })) {
      server.openFile(uri, text);

      var completions = server.completions(uri, new Position(3, 10));
      System.out.println("Completions: " + completions);

      var definition = server.definition(uri, new Position(3, 10));
      System.out.println("Definition: " + definition);
    }
  }
}
```

## File Lifecycle

The embedded API is document-oriented. The usual sequence is:

1. `openFile(uri, text)`
2. `changeFile(uri, updatedText)` whenever the in-memory text changes
3. `completions(uri, position)` and `definition(uri, position)` as needed
4. `closeFile(uri)` when the file is no longer active

Methods on `CoreFacade`:

- `openFile(String uri, String text)`: stores content, indexes it, returns diagnostics
- `changeFile(String uri, String text)`: replaces content, reindexes it, returns diagnostics
- `closeFile(String uri)`: clears document state, symbol ownership, and publishes empty diagnostics
- `analyze(String uri)`: re-runs analysis against the currently stored content
- `completions(String uri, Position position)`: returns completion candidates
- `definition(String uri, Position position)`: returns an optional definition location

## Diagnostics

Diagnostics are delivered in two ways:

- as the return value from `openFile`, `changeFile`, and `analyze`
- through the `DiagnosticsPublisher` callback passed to `CoreServer.createDefault(...)`

That callback is the embedded equivalent of LSP `publishDiagnostics`.

## URI and Position Conventions

- Use normal file URIs such as `Path.of(...).toUri().toString()`
- Positions are zero-based: `new Position(line, column)`

## When To Use `jvmpls-server` Instead

Use `jvmpls-server` only when you need an actual LSP server over stdio/JSON-RPC for editors and IDEs.

Use `jvmpls-core` when:

- your application already runs in-process
- you want direct method calls instead of LSP transport
- you want to own diagnostics, lifecycle, and threading locally
