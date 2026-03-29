# CLAUDE.md / AGENTS.md

This file provides guidance to Claude Code (claude.ai/code) and codex when working with code in this repository.

## Project Overview

jvm-pls is a polyglot language server for the JVM. It provides a unified symbol table across multiple JVM languages (Java, Groovy, with more planned) enabling cross-language navigation, completions, and diagnostics.

## Build Commands

Requires **Maven 3.9.9+** and **Java 21+**.

```shell
# Full build (install artifacts then run unit tests)
mvn -q -DskipTests install
mvn -q test

# Single plugin tests
mvn -pl jvmpls-java -Dtest='*JavaPlugin*Test' test
mvn -pl jvmpls-groovy -Dtest='*GroovyPlugin*Test' test

# Integration tests (cross-language scenarios, uses Failsafe)
mvn -pl jvmpls-it -Dit.test='*IT' verify
```

Integration tests require `mvn install` first since jvmpls-it depends on all plugin modules.

## Architecture

**Maven multi-module** (`se.alipsa.jvmpls`): `jvmpls-core`, `jvmpls-java`, `jvmpls-groovy`, `jvmpls-it`.

### Core flow

1. Editor calls `CoreFacade.openFile()`/`changeFile()` on the `CoreEngine`
2. `PluginRegistry.forFile()` asks each `JvmLangPlugin` to `claim()` the file (confidence score)
3. Winning plugin's `index()` parses the file and reports symbols via `SymbolReporter`
4. Symbols are stored in `SymbolIndex` (FQN-keyed unified table, per-file scoping)
5. On completions/definitions, the owning plugin queries the shared `SymbolIndex` through `CoreQuery`

### Plugin system

Plugins implement `JvmLangPlugin` and are discovered via **Java ServiceLoader** (`META-INF/services/se.alipsa.jvmpls.core.JvmLangPlugin`). Each plugin:
- Owns a set of file extensions and uses `claim()` for routing
- Parses using language-native tools (javac compiler API for Java, Groovy AST builder for Groovy)
- Reports symbols (packages, classes, methods, fields) to the unified index
- Resolves symbols and provides completions using both its own cached AST state and the shared index
- Manages its own per-file caches (cleared via `forget()`)

### Key classes

- `CoreEngine` — orchestrates plugins, index, document store, dependency graph
- `CoreServer` — high-level in-process server wrapper with diagnostics publishing
- `SymbolIndex` (implements `CoreQuery`) — central FQN-to-SymbolInfo map
- `DocumentStore` — in-memory open file content
- `PluginRegistry` — ServiceLoader-based plugin discovery
- `JavaPlugin` / `GroovyPlugin` — language implementations

### Model types

All in `se.alipsa.jvmpls.core.model`: `SymbolInfo` (with Kind enum), `CompletionItem`, `Diagnostic`, `Location`, `Position`, `Range`, `TextEdit`.

### Test conventions

- Unit tests live in each plugin module (`*Test.java` suffix, Surefire)
- Integration tests in `jvmpls-it` (`*IT.java` suffix, Failsafe)
- Test package prefix: `test.alipsa.jvmpls.*` (not `se.alipsa.jvmpls.*`)
- Tests use temp directories for file-based scenarios and inline source strings
