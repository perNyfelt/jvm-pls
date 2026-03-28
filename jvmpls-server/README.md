# jvmpls-server

`jvmpls-server` is the standalone Language Server Protocol transport for `jvm-pls`.

## Module Purpose

This module wraps `jvmpls-core`, `jvmpls-java`, `jvmpls-groovy`, and `jvmpls-classpath` behind an LSP4J-based stdio server so editors and IDEs can talk to `jvm-pls` over JSON-RPC.

Use this module when you need a real LSP server process. If you are embedding `jvm-pls` inside your own application, use `jvmpls-core` instead.

## Build And Run

Build the standalone shaded JAR:

```bash
mvn -pl jvmpls-server -am package
```

Run the server over stdio:

```bash
java -jar jvmpls-server/target/jvmpls-server-1.0.0-SNAPSHOT-standalone.jar --stdio
```

Other supported flags:

```bash
java -jar jvmpls-server/target/jvmpls-server-1.0.0-SNAPSHOT-standalone.jar --version
java -jar jvmpls-server/target/jvmpls-server-1.0.0-SNAPSHOT-standalone.jar --help
```

## What It Provides

- full document sync over LSP
- completions
- go-to-definition
- diagnostics publication
- proper shutdown and exit lifecycle handling

## Current Classpath Scope

The standalone server does not infer the workspace dependency graph yet. Today it can expose JDK symbols, but workspace dependency JARs and build output need future workspace/build-tool wiring.

## Embedding Note

This module is not intended to be consumed as a library API. Its public types mainly exist to support the LSP transport and tests. For in-process usage, start from `jvmpls-core/README.md`.
