# jvmpls-groovy

`jvmpls-groovy` is the Groovy language plugin for `jvm-pls`.

## Module Purpose

This module parses Groovy source files, reports packages/types/members into the shared symbol index, and implements Groovy-specific symbol resolution, completions, and definitions.

It is the module that makes `.groovy` and related Groovy script extensions understandable to `jvmpls-core` and `jvmpls-server`.

## Typical Usage

Add it to the runtime classpath together with `jvmpls-core`.

```xml
<dependency>
  <groupId>se.alipsa.jvmpls</groupId>
  <artifactId>jvmpls-core</artifactId>
  <version>1.0.0-SNAPSHOT</version>
</dependency>

<dependency>
  <groupId>se.alipsa.jvmpls</groupId>
  <artifactId>jvmpls-groovy</artifactId>
  <version>1.0.0-SNAPSHOT</version>
</dependency>
```

`CoreServer` discovers `GroovyPlugin` automatically through `ServiceLoader`, so normal embeddings do not need manual plugin registration.

```java
try (CoreServer server = CoreServer.createDefault(diagnosticsPublisher)) {
  server.openFile("file:///workspace/src/main/groovy/demo/Hello.groovy", sourceText);
}
```

## What It Handles

- file extensions: `.groovy`, `.gvy`, `.gy`, `.gsh`
- Groovy AST-based indexing
- package/import/alias-aware symbol resolution
- Groovy type completions, including default Groovy imports
- go-to-definition for indexed source and external symbols

## With External Dependencies

For JDK and dependency-JAR completions and definitions, add `jvmpls-classpath` at runtime as well. Once installed, Groovy lookups reuse the same shared `CoreQuery` path as source symbols.
