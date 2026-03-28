# jvmpls-classpath

`jvmpls-classpath` adds external symbol resolution for `jvm-pls`.

## Module Purpose

This module scans the JDK and dependency classpath, then exposes those symbols through the lazy `SymbolProvider` API in `jvmpls-core`.

Use it when you want completions and definitions for types that are not defined in the currently open source files, such as:

- JDK types like `java.util.List`
- classes from dependency JARs
- packages contributed by the runtime classpath

## Typical Usage

Most embeddings do not use this module directly. Add it to the runtime classpath next to `jvmpls-core`, then let `CoreServer` discover it through `ServiceLoader`.

```xml
<dependency>
  <groupId>se.alipsa.jvmpls</groupId>
  <artifactId>jvmpls-classpath</artifactId>
  <version>1.0.0-SNAPSHOT</version>
</dependency>
```

With that dependency present, the default embedded server will automatically register the provider module, but only the current JDK is scanned by default:

```java
try (CoreServer server = CoreServer.createDefault(diagnosticsPublisher)) {
  // JDK symbols are available
}
```

## Explicit Classpath And JDK Configuration

If your host application needs dependency JARs, compiled output directories, or a specific JDK home, use the explicit overload:

```java
List<String> classpath = List.of(
    "/path/to/dependency-a.jar",
    "/path/to/build/classes/java/main"
);
Path targetJdk = Path.of("/path/to/jdk");

try (CoreServer server = CoreServer.createDefault(diagnosticsPublisher, classpath, targetJdk)) {
  // external symbols come from the supplied classpath and JDK
}
```

This is the recommended way to model a real workspace. `jvmpls-classpath` does not assume the host process classpath is the same thing as the project classpath.

## Main Types

- `ClasspathSymbolProviderFactory`: `ServiceLoader` entry point used by `jvmpls-core`
- `ClasspathScanner`: scans classpath directories and JARs
- `JdkIndex`: scans the running JDK or an explicit JDK home
- `BinaryTypeReader`: reads `.class` metadata lazily with ASM
- `ClasspathSymbolProvider`: answers `CoreQuery` lookups from scanned binary symbols

## When Not To Use It

Do not depend on this module by itself. It is an extension for `jvmpls-core`, not a standalone API or server.

## Why is jvmpls-classpath a separate module? 

jvmpls-classpath is separate because it solves a different problem than the core engine.

jvmpls-core is the transport-agnostic document engine: open/change/analyze/completion/definition over source files plus the shared symbol index. jvmpls-classpath is an optional external symbol provider that scans JDKs, directories, and JARs using heavier dependencies like ClassGraph and ASM. Keeping that separate gives a few practical benefits:

- It keeps jvmpls-core smaller and cleaner. The core API does not need to know how classpath scanning works.
- It preserves the provider architecture introduced in Phase 2. Core owns the SymbolProvider contract; external symbol sources plug in via ServiceLoader.
- It makes the classpath feature optional. An embedding that only wants source-file indexing does not need ClassGraph/ASM or JDK scanning on its runtime
  classpath.
- It avoids coupling future external-symbol work to the engine. If classpath scanning changes, or another external provider is added later, that can happen
  without turning jvmpls-core into a grab bag.
- It makes testing cleaner. The integration surface between core and external providers is explicit, and the scanner can be tested independently.

So the short version is: jvmpls-core defines the symbol lookup mechanism, while jvmpls-classpath is one concrete provider implementation for external binary
symbols.
