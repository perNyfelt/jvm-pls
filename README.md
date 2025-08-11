# jvm-pls - JVM Polyglot Language Server

## 1. Core JVM Language Server
   Responsibilities:

- Manage project/workspace lifecycle: open, close, config changes. 
- Handle classpath, module info, build output directories. 
- Maintain a unified symbol table and type system representing all JVM classes/types. 
- Provide LSP basics: diagnostics, completions, definitions, references, hover. 
- Coordinate incremental analysis and caching. 
- Serve as the communication hub with editors (via LSP protocol).

Key Data Structures:

- Unified symbol index for all loaded classes/types/methods/fields across languages. 
- Per-source-file AST storage for each language. 
- Dependency graph between source files across languages. 
- Classpath and compiled binary index for external jars.

## 2. Language Plugins

Each language plugin handles parsing, semantic analysis, and AST construction for its language.

### Interface with Core:

- Parse source files into ASTs. 
- Report symbols (classes, methods, fields, annotations) to core symbol table. 
- Provide language-specific completions, diagnostics, refactorings. 
- Implement type resolution hooks to communicate with core unified symbol table. 
- Handle language-specific semantics (e.g., Groovy’s dynamic typing, Kotlin’s nullability).

### Example plugins:

- Java Plugin: Uses Java Compiler API (Javac) or Eclipse JDT parser.
- Groovy Plugin: Uses Groovy parser and semantic analyzer.
- Kotlin Plugin: Uses Kotlin compiler frontend.
- Scala Plugin: Uses Scala compiler APIs.
- Clojure Plugin: Uses Clojure compiler hooks.
- Nashorn JS Plugin: Uses JavaScript parser, but limited typing.

## 3. Unified Symbol Table & Type System
Provides a language-agnostic symbol and type model, tracking classes, interfaces, enums, methods, fields, annotations, type hierarchies, generics, etc.

### Features:

- Cross-language resolution: e.g., Groovy code resolving Kotlin classes, Kotlin implementing Java interfaces. 
- Model dynamic features as best as possible (e.g., Groovy’s dynamic calls). 
- Lazy resolution & incremental updates to keep performance manageable. 
- Link to compiled class files for binary types outside source.

## 4. Cross-Language Dependency Graph
Tracks source file dependencies within and across languages.

- Enables incremental compilation and reanalysis on file changes. 
- Supports “jump to definition” and “find references” even if targets are in different JVM languages.

## 5. Build Tool Integration
Hooks into build systems (Gradle, Maven, Bazel, Uso) to get:

### Project/module info.

- Class paths 
- Compilation outputs. 
- Build events for incremental updates.

## 6. Incremental Analysis & Caching
Uses fine-grained file change detection.

- Re-parses and re-analyzes only affected files and their dependents. 
- Caches ASTs, symbol tables, and type info to speed up subsequent queries.

## 7. Editor/IDE Integration
Exposes full LSP interface for:

- Syntax and semantic diagnostics. 
- Auto-completion. 
- Code navigation (go-to-definition, find references). 
- Rename/refactoring support. 
- Hover info, signature help, code actions. 
- Optional language-specific extensions (e.g., Groovy's dynamic type inference hints).

### Example Workflow
1. Editor opens a mixed Java + Groovy + Kotlin project. 
2. Core server loads build config, scans all source files. 
3. Language plugins parse their files, report symbols to core. 
4. Core builds unified symbol table, resolves cross-language references. 
5. User edits a Groovy file referencing a Kotlin class. 
6. Groovy plugin asks core to resolve symbol → core consults Kotlin plugin’s symbols. 
7. User requests "go to definition" on Kotlin class in Groovy code → core returns location. 
8. User adds new import via quick fix → core and Groovy plugin update internal state incrementally.

## Additional Considerations
### Dynamic Languages:
Some JVM languages (Groovy, Nashorn JS, Clojure) are highly dynamic — consider providing “best-effort” typing, or “unknown” types fallback with heuristics.

### Language-Specific Features:
Plugins can extend core with language-specific commands, code generation, refactorings.

### Concurrency:
Handle multiple requests and file changes asynchronously to keep responsiveness.

### Extensibility:
Allow third-party plugins (e.g., Lombok plugin, framework-specific plugins) to hook into core and extend features.

## Summary Diagram
```
+-------------------------------------------------------------+
|                    JVM Polyglot Language Server             |
|                                                             |
|  +------------------+       +----------------------------+  |
|  |   Language       |       |     Unified Symbol Table   |  |
|  |   Plugins        |<----->|   & Cross-Language Model   |  |
|  | (Java, Groovy,   |       +----------------------------+  |
|  |  Kotlin, Scala,  |                    ^                  |
|  |  Clojure, JS)    |                    |                  |
|  +------------------+                    |                  |
|           ^                            Cross-language       |
|           |                            dependency graph     |
|           |                                                 |
|    +------------------+                                     |
|    | Build Tool       |                                     |
|    | Integration      |                                     |
|    +------------------+                                     |
|                                                             |
|                  Editor (via LSP)                           |
+-------------------------------------------------------------+
```
## Plugin Interface Design
   Each language plugin should implement a well-defined interface that the core language server can use to:

- Parse & Analyze Source Code 
- Report Symbols and Types 
- Resolve Symbols & Types on Request 
- Provide Language-Specific LSP Features