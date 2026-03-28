# Phase 1: LSP Transport & Server Shell — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Create the `jvmpls-server` module that exposes the existing `CoreFacade` over LSP via LSP4J 1.0.0, launchable as `java -jar jvmpls-server.jar --stdio`.

**Architecture:** A new Maven module `jvmpls-server` depends on `jvmpls-core`, `jvmpls-java`, `jvmpls-groovy`, and LSP4J 1.0.0. It implements LSP4J's `LanguageServer` and `TextDocumentService` interfaces, bridging each LSP method to the existing `CoreFacade`/`CoreServer`. The existing in-process `CoreServer` stays unchanged for tests and embedding.

**Tech Stack:** Java 21, LSP4J 1.0.0 (`org.eclipse.lsp4j`), Maven, maven-assembly-plugin.

**Existing code context:**
- `CoreFacade` (interface): `openFile`, `changeFile`, `closeFile`, `analyze`, `completions`, `definition`
- `CoreServer` (class): implements `CoreFacade`, has `createDefault(DiagnosticsPublisher)`, delegates to `CoreEngine`, publishes diagnostics
- Model types: `Diagnostic` (range, message, severity, source, code), `CompletionItem` (label, detail, insertText, location, additionalTextEdits), `Location` (uri, range), `Position` (line, column), `Range` (start, end), `TextEdit` (range, newText)
- `Diagnostic.Severity`: ERROR, WARNING, INFORMATION, HINT
- `SymbolInfo.Kind`: PACKAGE, CLASS, INTERFACE, ENUM, METHOD, FIELD, ANNOTATION
- Positions and ranges are zero-based
- Test package prefix: `test.alipsa.jvmpls.*`

---

## File Structure

```
jvmpls-server/
├── pom.xml
└── src/
    ├── main/
    │   ├── java/se/alipsa/jvmpls/server/
    │   │   ├── JvmPlsLanguageServer.java    (LanguageServer impl, lifecycle)
    │   │   ├── JvmPlsTextDocumentService.java (TextDocumentService impl)
    │   │   ├── JvmPlsWorkspaceService.java  (WorkspaceService stub)
    │   │   ├── LspTypeConverter.java        (core model <-> LSP4J type mapping)
    │   │   └── Main.java                    (CLI entry point, stdin/stdout launcher)
    │   └── resources/
    │       └── META-INF/
    │           └── services/
    │               └── se.alipsa.jvmpls.core.JvmLangPlugin  (empty — plugins come from transitives)
    └── test/
        └── java/test/alipsa/jvmpls/server/
            ├── LspTypeConverterTest.java
            └── JvmPlsLanguageServerTest.java
```

Also modified:
- `pom.xml` (root aggregator — add `jvmpls-server` module)

---

### Task 1: Create the `jvmpls-server` Maven module skeleton

**Files:**
- Create: `jvmpls-server/pom.xml`
- Modify: `pom.xml` (root — add module)

- [ ] **Step 1: Add the module to the root POM**

In `pom.xml` (root), add `jvmpls-server` to the modules list, after `jvmpls-groovy` and before `jvmpls-it`. Also add LSP4J version to `<dependencyManagement>`:

```xml
  <modules>
    <module>jvmpls-core</module>
    <module>jvmpls-java</module>
    <module>jvmpls-groovy</module>
    <module>jvmpls-server</module>
    <module>jvmpls-it</module>
  </modules>
```

And in `<dependencyManagement><dependencies>`, add:

```xml
      <dependency>
        <groupId>org.eclipse.lsp4j</groupId>
        <artifactId>lsp4j</artifactId>
        <version>1.0.0</version>
      </dependency>
```

- [ ] **Step 2: Create the module POM**

Create `jvmpls-server/pom.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>se.alipsa.jvmpls</groupId>
    <artifactId>jvmpls-aggregator</artifactId>
    <version>1.0.0-SNAPSHOT</version>
  </parent>

  <artifactId>jvmpls-server</artifactId>

  <dependencies>
    <dependency>
      <groupId>se.alipsa.jvmpls</groupId>
      <artifactId>jvmpls-core</artifactId>
      <version>${project.version}</version>
    </dependency>
    <dependency>
      <groupId>se.alipsa.jvmpls</groupId>
      <artifactId>jvmpls-java</artifactId>
      <version>${project.version}</version>
    </dependency>
    <dependency>
      <groupId>se.alipsa.jvmpls</groupId>
      <artifactId>jvmpls-groovy</artifactId>
      <version>${project.version}</version>
    </dependency>
    <dependency>
      <groupId>org.eclipse.lsp4j</groupId>
      <artifactId>lsp4j</artifactId>
    </dependency>
    <dependency>
      <groupId>org.junit.jupiter</groupId>
      <artifactId>junit-jupiter</artifactId>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-surefire-plugin</artifactId>
      </plugin>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-assembly-plugin</artifactId>
        <version>3.7.1</version>
        <configuration>
          <archive>
            <manifest>
              <mainClass>se.alipsa.jvmpls.server.Main</mainClass>
            </manifest>
          </archive>
          <descriptorRefs>
            <descriptorRef>jar-with-dependencies</descriptorRef>
          </descriptorRefs>
          <appendAssemblyId>true</appendAssemblyId>
        </configuration>
        <executions>
          <execution>
            <id>make-standalone</id>
            <phase>package</phase>
            <goals>
              <goal>single</goal>
            </goals>
          </execution>
        </executions>
      </plugin>
    </plugins>
  </build>
</project>
```

- [ ] **Step 3: Create source directories**

```bash
mkdir -p jvmpls-server/src/main/java/se/alipsa/jvmpls/server
mkdir -p jvmpls-server/src/test/java/test/alipsa/jvmpls/server
```

- [ ] **Step 4: Verify the module compiles**

```bash
mvn -q -pl jvmpls-server -am compile
```

Expected: BUILD SUCCESS (empty module compiles with dependencies resolved).

- [ ] **Step 5: Commit**

```bash
git add jvmpls-server/pom.xml pom.xml
git commit -m "feat: add jvmpls-server module skeleton with LSP4J dependency"
```

---

### Task 2: Implement `LspTypeConverter`

This is a stateless utility class that maps between `se.alipsa.jvmpls.core.model.*` types and `org.eclipse.lsp4j.*` types. Every other class depends on this, so build it first with tests.

**Files:**
- Create: `jvmpls-server/src/main/java/se/alipsa/jvmpls/server/LspTypeConverter.java`
- Create: `jvmpls-server/src/test/java/test/alipsa/jvmpls/server/LspTypeConverterTest.java`

- [ ] **Step 1: Write the failing tests**

Create `jvmpls-server/src/test/java/test/alipsa/jvmpls/server/LspTypeConverterTest.java`:

```java
package test.alipsa.jvmpls.server;

import org.eclipse.lsp4j.*;
import org.junit.jupiter.api.Test;
import se.alipsa.jvmpls.server.LspTypeConverter;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LspTypeConverterTest {

    @Test
    void convertPosition() {
        var core = new se.alipsa.jvmpls.core.model.Position(5, 12);
        Position lsp = LspTypeConverter.toLsp(core);
        assertEquals(5, lsp.getLine());
        assertEquals(12, lsp.getCharacter());
    }

    @Test
    void convertRange() {
        var core = new se.alipsa.jvmpls.core.model.Range(
                new se.alipsa.jvmpls.core.model.Position(1, 0),
                new se.alipsa.jvmpls.core.model.Position(1, 10));
        Range lsp = LspTypeConverter.toLsp(core);
        assertEquals(1, lsp.getStart().getLine());
        assertEquals(0, lsp.getStart().getCharacter());
        assertEquals(1, lsp.getEnd().getLine());
        assertEquals(10, lsp.getEnd().getCharacter());
    }

    @Test
    void convertLocation() {
        var core = new se.alipsa.jvmpls.core.model.Location(
                "file:///tmp/Hello.java",
                new se.alipsa.jvmpls.core.model.Range(
                        new se.alipsa.jvmpls.core.model.Position(0, 0),
                        new se.alipsa.jvmpls.core.model.Position(0, 5)));
        Location lsp = LspTypeConverter.toLsp(core);
        assertEquals("file:///tmp/Hello.java", lsp.getUri());
        assertEquals(0, lsp.getRange().getStart().getLine());
    }

    @Test
    void convertDiagnostic_allSeverities() {
        for (var sev : se.alipsa.jvmpls.core.model.Diagnostic.Severity.values()) {
            var core = new se.alipsa.jvmpls.core.model.Diagnostic(
                    new se.alipsa.jvmpls.core.model.Range(
                            new se.alipsa.jvmpls.core.model.Position(0, 0),
                            new se.alipsa.jvmpls.core.model.Position(0, 1)),
                    "test message", sev, "java", "test-code");
            Diagnostic lsp = LspTypeConverter.toLsp(core);
            assertEquals("test message", lsp.getMessage());
            assertEquals("java", lsp.getSource());
            assertNotNull(lsp.getSeverity());
        }
    }

    @Test
    void convertDiagnostic_severityMapping() {
        var error = makeDiag(se.alipsa.jvmpls.core.model.Diagnostic.Severity.ERROR);
        assertEquals(DiagnosticSeverity.Error, LspTypeConverter.toLsp(error).getSeverity());

        var warn = makeDiag(se.alipsa.jvmpls.core.model.Diagnostic.Severity.WARNING);
        assertEquals(DiagnosticSeverity.Warning, LspTypeConverter.toLsp(warn).getSeverity());

        var info = makeDiag(se.alipsa.jvmpls.core.model.Diagnostic.Severity.INFORMATION);
        assertEquals(DiagnosticSeverity.Information, LspTypeConverter.toLsp(info).getSeverity());

        var hint = makeDiag(se.alipsa.jvmpls.core.model.Diagnostic.Severity.HINT);
        assertEquals(DiagnosticSeverity.Hint, LspTypeConverter.toLsp(hint).getSeverity());
    }

    @Test
    void convertCompletionItem() {
        var core = new se.alipsa.jvmpls.core.model.CompletionItem(
                "MyClass", "com.example.MyClass", "MyClass", null);
        CompletionItem lsp = LspTypeConverter.toLsp(core);
        assertEquals("MyClass", lsp.getLabel());
        assertEquals("com.example.MyClass", lsp.getDetail());
        assertEquals("MyClass", lsp.getInsertText());
    }

    @Test
    void convertCompletionItem_withTextEdits() {
        var edit = new se.alipsa.jvmpls.core.model.TextEdit(
                new se.alipsa.jvmpls.core.model.Range(
                        new se.alipsa.jvmpls.core.model.Position(0, 0),
                        new se.alipsa.jvmpls.core.model.Position(0, 0)),
                "import com.example.MyClass;\n");
        var core = new se.alipsa.jvmpls.core.model.CompletionItem(
                "MyClass", "com.example.MyClass", "MyClass", null, List.of(edit));
        CompletionItem lsp = LspTypeConverter.toLsp(core);
        assertNotNull(lsp.getAdditionalTextEdits());
        assertEquals(1, lsp.getAdditionalTextEdits().size());
        assertEquals("import com.example.MyClass;\n",
                lsp.getAdditionalTextEdits().get(0).getNewText());
    }

    @Test
    void convertLspPositionToCore() {
        var lsp = new Position(3, 7);
        se.alipsa.jvmpls.core.model.Position core = LspTypeConverter.toCore(lsp);
        assertEquals(3, core.line);
        assertEquals(7, core.column);
    }

    private static se.alipsa.jvmpls.core.model.Diagnostic makeDiag(
            se.alipsa.jvmpls.core.model.Diagnostic.Severity sev) {
        return new se.alipsa.jvmpls.core.model.Diagnostic(
                new se.alipsa.jvmpls.core.model.Range(
                        new se.alipsa.jvmpls.core.model.Position(0, 0),
                        new se.alipsa.jvmpls.core.model.Position(0, 1)),
                "msg", sev, "src", "code");
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
mvn -pl jvmpls-server -am -Dtest='LspTypeConverterTest' test
```

Expected: compilation error — `LspTypeConverter` does not exist.

- [ ] **Step 3: Implement `LspTypeConverter`**

Create `jvmpls-server/src/main/java/se/alipsa/jvmpls/server/LspTypeConverter.java`:

```java
package se.alipsa.jvmpls.server;

import org.eclipse.lsp4j.*;

import java.util.List;

/**
 * Stateless conversions between jvmpls core model types and LSP4J types.
 */
public final class LspTypeConverter {

    private LspTypeConverter() {}

    // ---- core -> LSP ----

    public static Position toLsp(se.alipsa.jvmpls.core.model.Position p) {
        return new Position(p.line, p.column);
    }

    public static Range toLsp(se.alipsa.jvmpls.core.model.Range r) {
        return new Range(toLsp(r.start), toLsp(r.end));
    }

    public static Location toLsp(se.alipsa.jvmpls.core.model.Location loc) {
        return new Location(loc.getUri(), toLsp(loc.getRange()));
    }

    public static DiagnosticSeverity toLsp(se.alipsa.jvmpls.core.model.Diagnostic.Severity sev) {
        return switch (sev) {
            case ERROR -> DiagnosticSeverity.Error;
            case WARNING -> DiagnosticSeverity.Warning;
            case INFORMATION -> DiagnosticSeverity.Information;
            case HINT -> DiagnosticSeverity.Hint;
        };
    }

    public static Diagnostic toLsp(se.alipsa.jvmpls.core.model.Diagnostic d) {
        Diagnostic lsp = new Diagnostic();
        lsp.setRange(toLsp(d.getRange()));
        lsp.setMessage(d.getMessage());
        lsp.setSeverity(toLsp(d.getSeverity()));
        lsp.setSource(d.getSource());
        if (d.getCode() != null) {
            lsp.setCode(d.getCode());
        }
        return lsp;
    }

    public static TextEdit toLsp(se.alipsa.jvmpls.core.model.TextEdit te) {
        return new TextEdit(toLsp(te.getRange()), te.getNewText());
    }

    public static CompletionItem toLsp(se.alipsa.jvmpls.core.model.CompletionItem ci) {
        CompletionItem lsp = new CompletionItem(ci.getLabel());
        lsp.setDetail(ci.getDetail());
        lsp.setInsertText(ci.getInsertText());
        if (ci.getAdditionalTextEdits() != null && !ci.getAdditionalTextEdits().isEmpty()) {
            lsp.setAdditionalTextEdits(
                    ci.getAdditionalTextEdits().stream()
                            .map(LspTypeConverter::toLsp)
                            .toList());
        }
        return lsp;
    }

    public static List<Diagnostic> toLspDiagnostics(
            List<se.alipsa.jvmpls.core.model.Diagnostic> diags) {
        return diags.stream().map(LspTypeConverter::toLsp).toList();
    }

    public static List<CompletionItem> toLspCompletionItems(
            List<se.alipsa.jvmpls.core.model.CompletionItem> items) {
        return items.stream().map(LspTypeConverter::toLsp).toList();
    }

    // ---- LSP -> core ----

    public static se.alipsa.jvmpls.core.model.Position toCore(Position p) {
        return new se.alipsa.jvmpls.core.model.Position(p.getLine(), p.getCharacter());
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
mvn -pl jvmpls-server -am -Dtest='LspTypeConverterTest' test
```

Expected: all tests PASS.

- [ ] **Step 5: Commit**

```bash
git add jvmpls-server/src/
git commit -m "feat: add LspTypeConverter for core model <-> LSP4J mapping"
```

---

### Task 3: Implement `JvmPlsTextDocumentService`

Bridges LSP4J `TextDocumentService` to `CoreFacade`. Handles `didOpen`, `didChange`, `didClose`, `completion`, and `definition`.

**Files:**
- Create: `jvmpls-server/src/main/java/se/alipsa/jvmpls/server/JvmPlsTextDocumentService.java`

- [ ] **Step 1: Create the implementation**

Create `jvmpls-server/src/main/java/se/alipsa/jvmpls/server/JvmPlsTextDocumentService.java`:

```java
package se.alipsa.jvmpls.server;

import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.TextDocumentService;
import se.alipsa.jvmpls.core.CoreFacade;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public class JvmPlsTextDocumentService implements TextDocumentService {

    private final CoreFacade core;
    private volatile LanguageClient client;

    public JvmPlsTextDocumentService(CoreFacade core) {
        this.core = core;
    }

    void setClient(LanguageClient client) {
        this.client = client;
    }

    // ---- document sync ----

    @Override
    public void didOpen(DidOpenTextDocumentParams params) {
        String uri = params.getTextDocument().getUri();
        String text = params.getTextDocument().getText();
        var diags = core.openFile(uri, text);
        publishDiagnostics(uri, diags);
    }

    @Override
    public void didChange(DidChangeTextDocumentParams params) {
        String uri = params.getTextDocument().getUri();
        // Full sync: take the last content change (LSP spec says for Full sync there's exactly one)
        List<TextDocumentContentChangeEvent> changes = params.getContentChanges();
        if (changes.isEmpty()) return;
        String text = changes.get(changes.size() - 1).getText();
        var diags = core.changeFile(uri, text);
        publishDiagnostics(uri, diags);
    }

    @Override
    public void didClose(DidCloseTextDocumentParams params) {
        String uri = params.getTextDocument().getUri();
        core.closeFile(uri);
        publishDiagnostics(uri, List.of()); // clear diagnostics
    }

    @Override
    public void didSave(DidSaveTextDocumentParams params) {
        // no-op for now; reanalysis happens on didChange
    }

    // ---- completion ----

    @Override
    public CompletableFuture<Either<List<CompletionItem>, CompletionList>> completion(
            CompletionParams params) {
        return CompletableFuture.supplyAsync(() -> {
            String uri = params.getTextDocument().getUri();
            var pos = LspTypeConverter.toCore(params.getPosition());
            var items = core.completions(uri, pos);
            return Either.forLeft(LspTypeConverter.toLspCompletionItems(items));
        });
    }

    // ---- definition ----

    @Override
    public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> definition(
            DefinitionParams params) {
        return CompletableFuture.supplyAsync(() -> {
            String uri = params.getTextDocument().getUri();
            var pos = LspTypeConverter.toCore(params.getPosition());
            var loc = core.definition(uri, pos);
            List<Location> result = loc.map(l -> List.of(LspTypeConverter.toLsp(l)))
                    .orElse(List.of());
            return Either.forLeft(result);
        });
    }

    // ---- internal ----

    private void publishDiagnostics(String uri,
                                    List<se.alipsa.jvmpls.core.model.Diagnostic> diags) {
        LanguageClient c = client;
        if (c != null) {
            c.publishDiagnostics(new PublishDiagnosticsParams(
                    uri, LspTypeConverter.toLspDiagnostics(diags)));
        }
    }
}
```

- [ ] **Step 2: Verify it compiles**

```bash
mvn -pl jvmpls-server -am compile
```

Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add jvmpls-server/src/main/java/se/alipsa/jvmpls/server/JvmPlsTextDocumentService.java
git commit -m "feat: add JvmPlsTextDocumentService bridging LSP to CoreFacade"
```

---

### Task 4: Implement `JvmPlsWorkspaceService`

Minimal stub — required by LSP4J's `LanguageServer` interface.

**Files:**
- Create: `jvmpls-server/src/main/java/se/alipsa/jvmpls/server/JvmPlsWorkspaceService.java`

- [ ] **Step 1: Create the stub**

Create `jvmpls-server/src/main/java/se/alipsa/jvmpls/server/JvmPlsWorkspaceService.java`:

```java
package se.alipsa.jvmpls.server;

import org.eclipse.lsp4j.DidChangeConfigurationParams;
import org.eclipse.lsp4j.DidChangeWatchedFilesParams;
import org.eclipse.lsp4j.services.WorkspaceService;

public class JvmPlsWorkspaceService implements WorkspaceService {

    @Override
    public void didChangeConfiguration(DidChangeConfigurationParams params) {
        // will be implemented in Phase 3 (build system integration)
    }

    @Override
    public void didChangeWatchedFiles(DidChangeWatchedFilesParams params) {
        // will be implemented in Phase 3 (build file watching)
    }
}
```

- [ ] **Step 2: Verify it compiles**

```bash
mvn -pl jvmpls-server -am compile
```

Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add jvmpls-server/src/main/java/se/alipsa/jvmpls/server/JvmPlsWorkspaceService.java
git commit -m "feat: add JvmPlsWorkspaceService stub"
```

---

### Task 5: Implement `JvmPlsLanguageServer`

The main `LanguageServer` implementation. Handles `initialize`/`shutdown`/`exit` and wires up the services.

**Files:**
- Create: `jvmpls-server/src/main/java/se/alipsa/jvmpls/server/JvmPlsLanguageServer.java`

- [ ] **Step 1: Create the implementation**

Create `jvmpls-server/src/main/java/se/alipsa/jvmpls/server/JvmPlsLanguageServer.java`:

```java
package se.alipsa.jvmpls.server;

import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.services.*;
import se.alipsa.jvmpls.core.server.CoreServer;

import java.util.concurrent.CompletableFuture;

public class JvmPlsLanguageServer implements LanguageServer, LanguageClientAware {

    private final CoreServer coreServer;
    private final JvmPlsTextDocumentService textDocumentService;
    private final JvmPlsWorkspaceService workspaceService;
    private volatile LanguageClient client;
    private volatile boolean shutdownRequested;

    public JvmPlsLanguageServer() {
        this.coreServer = CoreServer.createDefault((uri, diags) -> {
            // Diagnostics are published by JvmPlsTextDocumentService directly;
            // this callback is unused in LSP mode but required by CoreServer.
        });
        this.textDocumentService = new JvmPlsTextDocumentService(coreServer);
        this.workspaceService = new JvmPlsWorkspaceService();
    }

    @Override
    public void connect(LanguageClient client) {
        this.client = client;
        this.textDocumentService.setClient(client);
    }

    @Override
    public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
        return CompletableFuture.supplyAsync(() -> {
            ServerCapabilities capabilities = new ServerCapabilities();

            // Full document sync — client sends entire file content on each change
            capabilities.setTextDocumentSync(TextDocumentSyncKind.Full);

            // Completion support
            CompletionOptions completionOptions = new CompletionOptions();
            completionOptions.setTriggerCharacters(java.util.List.of("."));
            capabilities.setCompletionProvider(completionOptions);

            // Definition support
            capabilities.setDefinitionProvider(true);

            ServerInfo serverInfo = new ServerInfo("jvm-pls", "1.0.0-SNAPSHOT");
            return new InitializeResult(capabilities, serverInfo);
        });
    }

    @Override
    public void initialized(InitializedParams params) {
        // workspace scanning will be added in Phase 3
    }

    @Override
    public CompletableFuture<Object> shutdown() {
        shutdownRequested = true;
        coreServer.close();
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void exit() {
        System.exit(shutdownRequested ? 0 : 1);
    }

    @Override
    public TextDocumentService getTextDocumentService() {
        return textDocumentService;
    }

    @Override
    public WorkspaceService getWorkspaceService() {
        return workspaceService;
    }
}
```

- [ ] **Step 2: Verify it compiles**

```bash
mvn -pl jvmpls-server -am compile
```

Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add jvmpls-server/src/main/java/se/alipsa/jvmpls/server/JvmPlsLanguageServer.java
git commit -m "feat: add JvmPlsLanguageServer with initialize/shutdown lifecycle"
```

---

### Task 6: Implement `Main` (CLI entry point)

Parses `--stdio`, `--version`, `--help` and launches the LSP server over stdin/stdout.

**Files:**
- Create: `jvmpls-server/src/main/java/se/alipsa/jvmpls/server/Main.java`

- [ ] **Step 1: Create the entry point**

Create `jvmpls-server/src/main/java/se/alipsa/jvmpls/server/Main.java`:

```java
package se.alipsa.jvmpls.server;

import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageClient;

import java.io.InputStream;
import java.io.OutputStream;

public final class Main {

    private static final String VERSION = "1.0.0-SNAPSHOT";

    private Main() {}

    public static void main(String[] args) throws Exception {
        for (String arg : args) {
            switch (arg) {
                case "--version" -> {
                    System.out.println("jvm-pls " + VERSION);
                    return;
                }
                case "--help" -> {
                    printUsage();
                    return;
                }
                case "--stdio" -> {} // default, no-op
                default -> {
                    System.err.println("Unknown option: " + arg);
                    printUsage();
                    System.exit(1);
                }
            }
        }

        startStdio(System.in, System.out);
    }

    static void startStdio(InputStream in, OutputStream out) throws Exception {
        JvmPlsLanguageServer server = new JvmPlsLanguageServer();
        Launcher<LanguageClient> launcher = LSPLauncher.createServerLauncher(server, in, out);
        server.connect(launcher.getRemoteProxy());
        launcher.startListening().get();
    }

    private static void printUsage() {
        System.out.println("Usage: java -jar jvmpls-server.jar [options]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --stdio    Launch LSP server over stdin/stdout (default)");
        System.out.println("  --version  Print version and exit");
        System.out.println("  --help     Print this help and exit");
    }
}
```

- [ ] **Step 2: Verify it compiles**

```bash
mvn -pl jvmpls-server -am compile
```

Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add jvmpls-server/src/main/java/se/alipsa/jvmpls/server/Main.java
git commit -m "feat: add Main CLI entry point with --stdio, --version, --help"
```

---

### Task 7: Integration test — LSP lifecycle over in-process streams

Tests the full LSP lifecycle: initialize, open a Java file, request completions, request definition, shutdown. Uses piped streams to simulate an LSP client.

**Files:**
- Create: `jvmpls-server/src/test/java/test/alipsa/jvmpls/server/JvmPlsLanguageServerTest.java`

- [ ] **Step 1: Write the test**

Create `jvmpls-server/src/test/java/test/alipsa/jvmpls/server/JvmPlsLanguageServerTest.java`:

```java
package test.alipsa.jvmpls.server;

import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import se.alipsa.jvmpls.server.JvmPlsLanguageServer;

import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class JvmPlsLanguageServerTest {

    private LanguageServer serverProxy;
    private TestLanguageClient testClient;
    private Thread serverThread;
    private PipedOutputStream clientToServer;
    private PipedOutputStream serverToClient;

    @BeforeEach
    void setUp() throws Exception {
        // Pipes: client writes -> server reads, server writes -> client reads
        var clientOut = new PipedOutputStream();
        var serverIn = new PipedInputStream(clientOut);
        var serverOut = new PipedOutputStream();
        var clientIn = new PipedInputStream(serverOut);

        this.clientToServer = clientOut;
        this.serverToClient = serverOut;

        // Start the server in a background thread
        JvmPlsLanguageServer server = new JvmPlsLanguageServer();
        testClient = new TestLanguageClient();

        // Server-side launcher
        Launcher<org.eclipse.lsp4j.services.LanguageClient> serverLauncher =
                LSPLauncher.createServerLauncher(server, serverIn, serverOut);
        server.connect(serverLauncher.getRemoteProxy());
        serverThread = new Thread(() -> {
            try { serverLauncher.startListening().get(); } catch (Exception ignored) {}
        }, "lsp-server");
        serverThread.setDaemon(true);
        serverThread.start();

        // Client-side launcher
        Launcher<LanguageServer> clientLauncher =
                LSPLauncher.createClientLauncher(testClient, clientIn, clientOut);
        serverProxy = clientLauncher.getRemoteProxy();
        Thread clientThread = new Thread(() -> {
            try { clientLauncher.startListening().get(); } catch (Exception ignored) {}
        }, "lsp-client");
        clientThread.setDaemon(true);
        clientThread.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (serverProxy != null) {
            serverProxy.shutdown().get(5, TimeUnit.SECONDS);
        }
        clientToServer.close();
        serverToClient.close();
    }

    @Test
    void initialize_returnsCapabilities() throws Exception {
        InitializeParams params = new InitializeParams();
        params.setCapabilities(new ClientCapabilities());
        InitializeResult result = serverProxy.initialize(params).get(5, TimeUnit.SECONDS);

        assertNotNull(result.getCapabilities());
        assertNotNull(result.getCapabilities().getCompletionProvider());
        assertTrue(result.getCapabilities().getCompletionProvider()
                .getTriggerCharacters().contains("."));
        assertNotNull(result.getServerInfo());
        assertEquals("jvm-pls", result.getServerInfo().getName());
    }

    @Test
    void didOpen_publishesDiagnostics() throws Exception {
        InitializeParams initParams = new InitializeParams();
        initParams.setCapabilities(new ClientCapabilities());
        serverProxy.initialize(initParams).get(5, TimeUnit.SECONDS);
        serverProxy.initialized(new InitializedParams());

        Path dir = Files.createTempDirectory("jvmpls-lsp-test");
        Path file = dir.resolve("Hello.java");
        String code = "package demo; public class Hello {}";
        Files.writeString(file, code, StandardCharsets.UTF_8);
        String uri = file.toUri().toString();

        serverProxy.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(
                new TextDocumentItem(uri, "java", 1, code)));

        // Wait for diagnostics to arrive
        testClient.awaitDiagnostics(uri, 5, TimeUnit.SECONDS);
    }

    @Test
    void completion_returnsResults() throws Exception {
        InitializeParams initParams = new InitializeParams();
        initParams.setCapabilities(new ClientCapabilities());
        serverProxy.initialize(initParams).get(5, TimeUnit.SECONDS);
        serverProxy.initialized(new InitializedParams());

        Path dir = Files.createTempDirectory("jvmpls-lsp-compl");
        Path file1 = dir.resolve("Foo.java");
        String code1 = "package demo; public class Foo {}";
        Files.writeString(file1, code1, StandardCharsets.UTF_8);
        String uri1 = file1.toUri().toString();

        Path file2 = dir.resolve("Bar.java");
        String code2 = "package demo; class Bar { Fo }";
        Files.writeString(file2, code2, StandardCharsets.UTF_8);
        String uri2 = file2.toUri().toString();

        serverProxy.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(
                new TextDocumentItem(uri1, "java", 1, code1)));
        serverProxy.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(
                new TextDocumentItem(uri2, "java", 1, code2)));

        // Small delay to let indexing complete
        Thread.sleep(200);

        // Request completions at position of "Fo" (line 0, column 27)
        CompletionParams cParams = new CompletionParams(
                new TextDocumentIdentifier(uri2),
                new Position(0, 27));
        var result = serverProxy.getTextDocumentService().completion(cParams)
                .get(5, TimeUnit.SECONDS);

        assertNotNull(result);
        List<CompletionItem> items = result.isLeft() ? result.getLeft() : result.getRight().getItems();
        boolean hasFoo = items.stream().anyMatch(i -> "Foo".equals(i.getLabel()));
        assertTrue(hasFoo, "Expected completion item 'Foo' but got: " +
                items.stream().map(CompletionItem::getLabel).toList());
    }

    // ---- test client ----

    private static class TestLanguageClient implements org.eclipse.lsp4j.services.LanguageClient {
        private final List<PublishDiagnosticsParams> diagnostics =
                Collections.synchronizedList(new ArrayList<>());

        @Override
        public void telemetryEvent(Object object) {}

        @Override
        public void publishDiagnostics(PublishDiagnosticsParams params) {
            diagnostics.add(params);
        }

        @Override
        public void showMessage(MessageParams messageParams) {}

        @Override
        public CompletableFuture<MessageActionItem> showMessageRequest(
                ShowMessageRequestParams requestParams) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void logMessage(MessageParams message) {}

        void awaitDiagnostics(String uri, long timeout, TimeUnit unit) throws Exception {
            long deadline = System.nanoTime() + unit.toNanos(timeout);
            while (System.nanoTime() < deadline) {
                if (diagnostics.stream().anyMatch(d -> uri.equals(d.getUri()))) return;
                Thread.sleep(50);
            }
            fail("Timed out waiting for diagnostics on " + uri);
        }
    }
}
```

- [ ] **Step 2: Run the test**

```bash
mvn -q -DskipTests install && mvn -pl jvmpls-server -Dtest='JvmPlsLanguageServerTest' test
```

Expected: all 3 tests PASS. The `install` is needed first to ensure plugin JARs are available to the server module via ServiceLoader.

- [ ] **Step 3: Commit**

```bash
git add jvmpls-server/src/test/
git commit -m "test: add LSP lifecycle integration tests for jvmpls-server"
```

---

### Task 8: Build the fat JAR and verify it launches

**Files:** No new files — uses existing maven-assembly-plugin config from Task 1.

- [ ] **Step 1: Build the full project with packaging**

```bash
mvn -q -DskipTests install && mvn -pl jvmpls-server package -DskipTests
```

Expected: BUILD SUCCESS. Produces `jvmpls-server/target/jvmpls-server-1.0.0-SNAPSHOT-jar-with-dependencies.jar`.

- [ ] **Step 2: Verify `--version` works**

```bash
java -jar jvmpls-server/target/jvmpls-server-1.0.0-SNAPSHOT-jar-with-dependencies.jar --version
```

Expected output: `jvm-pls 1.0.0-SNAPSHOT`

- [ ] **Step 3: Verify `--help` works**

```bash
java -jar jvmpls-server/target/jvmpls-server-1.0.0-SNAPSHOT-jar-with-dependencies.jar --help
```

Expected output:
```
Usage: java -jar jvmpls-server.jar [options]

Options:
  --stdio    Launch LSP server over stdin/stdout (default)
  --version  Print version and exit
  --help     Print this help and exit
```

- [ ] **Step 4: Verify ServiceLoader discovers plugins in the fat JAR**

```bash
jar tf jvmpls-server/target/jvmpls-server-1.0.0-SNAPSHOT-jar-with-dependencies.jar | grep 'META-INF/services/se.alipsa.jvmpls.core.JvmLangPlugin'
```

Expected: at least one match. If the assembly plugin doesn't merge ServiceLoader files (only one file wins), this needs fixing — see Step 5.

- [ ] **Step 5: Fix ServiceLoader merging if needed**

If Step 4 shows only one ServiceLoader file, the `jar-with-dependencies` descriptor doesn't merge `META-INF/services/` files. In that case, create a custom assembly descriptor that handles merging.

Create `jvmpls-server/src/assembly/standalone.xml`:

```xml
<assembly xmlns="http://maven.apache.org/ASSEMBLY/2.2.0"
          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
          xsi:schemaLocation="http://maven.apache.org/ASSEMBLY/2.2.0
            http://maven.apache.org/xsd/assembly-2.2.0.xsd">
  <id>standalone</id>
  <formats>
    <format>jar</format>
  </formats>
  <includeBaseDirectory>false</includeBaseDirectory>
  <dependencySets>
    <dependencySet>
      <outputDirectory>/</outputDirectory>
      <unpack>true</unpack>
      <unpackOptions>
        <excludes>
          <exclude>META-INF/services/**</exclude>
        </excludes>
      </unpackOptions>
    </dependencySet>
  </dependencySets>
  <fileSets>
    <fileSet>
      <directory>${project.build.directory}/merged-services</directory>
      <outputDirectory>META-INF/services</outputDirectory>
    </fileSet>
  </fileSets>
</assembly>
```

And add a `maven-antrun-plugin` execution in the POM to merge services files before assembly, or switch to `maven-shade-plugin` with `ServicesResourceTransformer`. If this is needed, update `jvmpls-server/pom.xml` to use shade instead:

```xml
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-shade-plugin</artifactId>
        <version>3.6.0</version>
        <executions>
          <execution>
            <id>make-standalone</id>
            <phase>package</phase>
            <goals>
              <goal>shade</goal>
            </goals>
            <configuration>
              <shadedArtifactAttached>true</shadedArtifactAttached>
              <shadedClassifierName>standalone</shadedClassifierName>
              <transformers>
                <transformer implementation="org.apache.maven.plugins.shade.resource.ManifestResourceTransformer">
                  <mainClass>se.alipsa.jvmpls.server.Main</mainClass>
                </transformer>
                <transformer implementation="org.apache.maven.plugins.shade.resource.ServicesResourceTransformer"/>
              </transformers>
            </configuration>
          </execution>
        </executions>
      </plugin>
```

Rebuild and re-verify:

```bash
mvn -pl jvmpls-server package -DskipTests
jar tf jvmpls-server/target/jvmpls-server-1.0.0-SNAPSHOT-standalone.jar | grep 'META-INF/services/se.alipsa.jvmpls.core.JvmLangPlugin'
```

Then read the merged file to verify both plugins are listed:

```bash
unzip -p jvmpls-server/target/jvmpls-server-1.0.0-SNAPSHOT-standalone.jar META-INF/services/se.alipsa.jvmpls.core.JvmLangPlugin
```

Expected: both `se.alipsa.jvmpls.java.JavaPlugin` and `se.alipsa.jvmpls.groovy.GroovyPlugin` are listed.

- [ ] **Step 6: Commit**

```bash
git add jvmpls-server/
git commit -m "feat: configure fat JAR packaging with ServiceLoader merging"
```

---

### Task 9: Run the full test suite

**Files:** No changes — verification only.

- [ ] **Step 1: Run all unit tests across the reactor**

```bash
mvn -q -DskipTests install && mvn -q test
```

Expected: all tests PASS (existing tests in core, java, groovy modules plus new server tests).

- [ ] **Step 2: Run integration tests**

```bash
mvn -pl jvmpls-it verify
```

Expected: all integration tests PASS (cross-language completions and definitions).

- [ ] **Step 3: Commit if any fixups were needed**

If any test failures required fixes, commit those fixes now. Otherwise, no commit needed.
