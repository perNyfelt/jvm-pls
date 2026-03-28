# jvmpls-java

`jvmpls-java` is the Java language plugin for `jvm-pls`.

## Module Purpose

This module parses Java source files, reports declared packages/types/members into the shared symbol index, and provides Java-specific symbol resolution, completions, and definitions.

It is the module that makes `.java` files understandable to `jvmpls-core` and `jvmpls-server`.

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
  <artifactId>jvmpls-java</artifactId>
  <version>1.0.0-SNAPSHOT</version>
</dependency>
```

`CoreServer` discovers `JavaPlugin` automatically through `ServiceLoader`, so no extra registration is required in the normal embedded or server paths.

```java
try (CoreServer server = CoreServer.createDefault(diagnosticsPublisher)) {
  server.openFile("file:///workspace/src/main/java/demo/Hello.java", sourceText);
}
```

## What It Handles

- file extension: `.java`
- source indexing through the JDK compiler APIs
- same-package and import-aware symbol resolution
- Java completions for visible types
- go-to-definition for indexed source and external symbols

## With External Dependencies

For JDK and dependency-JAR completions and definitions, add `jvmpls-classpath` at runtime as well. `jvmpls-java` uses the shared `CoreQuery` API, so external symbols become visible automatically once that provider is installed.
