package se.alipsa.jvmpls.server;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.ParameterInformation;
import org.eclipse.lsp4j.SignatureHelp;
import org.eclipse.lsp4j.SignatureInformation;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.SymbolKind;
import org.eclipse.lsp4j.WorkspaceEdit;

import se.alipsa.jvmpls.core.model.CodeActionInfo;
import se.alipsa.jvmpls.core.model.Diagnostic;
import se.alipsa.jvmpls.core.model.HoverInfo;
import se.alipsa.jvmpls.core.model.SignatureHelpInfo;
import se.alipsa.jvmpls.core.model.SymbolInfo;

/**
 * Stateless utility class that maps between {@code se.alipsa.jvmpls.core.model.*} types and {@code
 * org.eclipse.lsp4j.*} types.
 */
public final class LspTypeConverter {

  private LspTypeConverter() {
    // utility class
  }

  // -------------------------------------------------------------------------
  // Position
  // -------------------------------------------------------------------------

  public static org.eclipse.lsp4j.Position toLsp(se.alipsa.jvmpls.core.model.Position core) {
    return new org.eclipse.lsp4j.Position(core.line, core.column);
  }

  public static se.alipsa.jvmpls.core.model.Position toCore(org.eclipse.lsp4j.Position lsp) {
    return new se.alipsa.jvmpls.core.model.Position(lsp.getLine(), lsp.getCharacter());
  }

  // -------------------------------------------------------------------------
  // Range
  // -------------------------------------------------------------------------

  public static org.eclipse.lsp4j.Range toLsp(se.alipsa.jvmpls.core.model.Range core) {
    return new org.eclipse.lsp4j.Range(toLsp(core.start), toLsp(core.end));
  }

  public static se.alipsa.jvmpls.core.model.Range toCore(org.eclipse.lsp4j.Range lsp) {
    return new se.alipsa.jvmpls.core.model.Range(toCore(lsp.getStart()), toCore(lsp.getEnd()));
  }

  // -------------------------------------------------------------------------
  // Location
  // -------------------------------------------------------------------------

  public static org.eclipse.lsp4j.Location toLsp(se.alipsa.jvmpls.core.model.Location core) {
    return new org.eclipse.lsp4j.Location(core.getUri(), toLsp(core.getRange()));
  }

  // -------------------------------------------------------------------------
  // DiagnosticSeverity
  // -------------------------------------------------------------------------

  public static org.eclipse.lsp4j.DiagnosticSeverity toLsp(Diagnostic.Severity severity) {
    return switch (severity) {
      case ERROR -> org.eclipse.lsp4j.DiagnosticSeverity.Error;
      case WARNING -> org.eclipse.lsp4j.DiagnosticSeverity.Warning;
      case INFORMATION -> org.eclipse.lsp4j.DiagnosticSeverity.Information;
      case HINT -> org.eclipse.lsp4j.DiagnosticSeverity.Hint;
    };
  }

  // -------------------------------------------------------------------------
  // Diagnostic
  // -------------------------------------------------------------------------

  public static org.eclipse.lsp4j.Diagnostic toLsp(Diagnostic core) {
    org.eclipse.lsp4j.Diagnostic lsp = new org.eclipse.lsp4j.Diagnostic();
    lsp.setRange(toLsp(core.getRange()));
    lsp.setMessage(core.getMessage());
    lsp.setSeverity(toLsp(core.getSeverity()));
    lsp.setSource(core.getSource());
    if (core.getCode() != null) {
      lsp.setCode(core.getCode());
    }
    return lsp;
  }

  public static Diagnostic toCore(org.eclipse.lsp4j.Diagnostic lsp) {
    return new Diagnostic(
        toCore(lsp.getRange()),
        lsp.getMessage() == null
            ? ""
            : lsp.getMessage().isLeft()
                ? lsp.getMessage().getLeft()
                : lsp.getMessage().getRight().getValue(),
        switch (lsp.getSeverity()) {
          case Error -> Diagnostic.Severity.ERROR;
          case Warning -> Diagnostic.Severity.WARNING;
          case Information -> Diagnostic.Severity.INFORMATION;
          case Hint -> Diagnostic.Severity.HINT;
          case null -> Diagnostic.Severity.ERROR;
        },
        lsp.getSource(),
        lsp.getCode() == null ? null : lsp.getCode().getLeft());
  }

  // -------------------------------------------------------------------------
  // TextEdit
  // -------------------------------------------------------------------------

  public static org.eclipse.lsp4j.TextEdit toLsp(se.alipsa.jvmpls.core.model.TextEdit core) {
    return new org.eclipse.lsp4j.TextEdit(toLsp(core.getRange()), core.getNewText());
  }

  // -------------------------------------------------------------------------
  // CompletionItem
  // -------------------------------------------------------------------------

  public static org.eclipse.lsp4j.CompletionItem toLsp(
      se.alipsa.jvmpls.core.model.CompletionItem core) {
    org.eclipse.lsp4j.CompletionItem lsp = new org.eclipse.lsp4j.CompletionItem(core.getLabel());
    String detail = core.getDetail();
    String typeDetail = core.getTypeDetail();
    if (typeDetail != null && !typeDetail.isBlank()) {
      detail = (detail == null || detail.isBlank()) ? typeDetail : detail + " : " + typeDetail;
    }
    lsp.setDetail(detail);
    lsp.setInsertText(core.getInsertText());
    List<se.alipsa.jvmpls.core.model.TextEdit> edits = core.getAdditionalTextEdits();
    if (edits != null && !edits.isEmpty()) {
      lsp.setAdditionalTextEdits(
          edits.stream().map(LspTypeConverter::toLsp).collect(Collectors.toList()));
    }
    return lsp;
  }

  public static org.eclipse.lsp4j.Hover toLsp(HoverInfo core) {
    MarkupContent content = new MarkupContent();
    content.setKind("markdown");
    StringBuilder value = new StringBuilder();
    if (core.getTitle() != null && !core.getTitle().isBlank()) {
      value.append("```java\n").append(core.getTitle()).append("\n```");
    }
    if (core.getDetail() != null && !core.getDetail().isBlank()) {
      if (value.length() > 0) {
        value.append("\n\n");
      }
      value.append(core.getDetail());
    }
    if (core.getProvenance() != null && !core.getProvenance().isBlank()) {
      if (value.length() > 0) {
        value.append("\n\n");
      }
      value.append('_').append(core.getProvenance()).append('_');
    }
    if (core.getDocumentation() != null && !core.getDocumentation().isBlank()) {
      if (value.length() > 0) {
        value.append("\n\n");
      }
      value.append(core.getDocumentation());
    }
    content.setValue(value.toString());
    org.eclipse.lsp4j.Hover hover = new org.eclipse.lsp4j.Hover(content);
    if (core.getLocation() != null) {
      hover.setRange(toLsp(core.getLocation().getRange()));
    }
    return hover;
  }

  public static SignatureHelp toLsp(SignatureHelpInfo core) {
    SignatureHelp signatureHelp = new SignatureHelp();
    signatureHelp.setActiveSignature(core.getActiveSignature());
    signatureHelp.setActiveParameter(core.getActiveParameter());
    signatureHelp.setSignatures(
        core.getSignatures().stream()
            .map(
                callable -> {
                  SignatureInformation info = new SignatureInformation();
                  info.setLabel(callable.getLabel());
                  if (callable.getDocumentation() != null
                      && !callable.getDocumentation().isBlank()) {
                    info.setDocumentation(callable.getDocumentation());
                  }
                  info.setParameters(
                      callable.getParameterLabels().stream()
                          .map(ParameterInformation::new)
                          .collect(Collectors.toList()));
                  return info;
                })
            .collect(Collectors.toList()));
    return signatureHelp;
  }

  public static SymbolInformation toLspSymbol(SymbolInfo core) {
    SymbolInformation symbol = new SymbolInformation();
    symbol.setName(symbolName(core));
    symbol.setKind(toLsp(core.getKind()));
    symbol.setLocation(toLsp(core.getLocation()));
    if (core.getContainerFqName() != null && !core.getContainerFqName().isBlank()) {
      symbol.setContainerName(core.getContainerFqName());
    }
    return symbol;
  }

  public static CodeAction toLsp(String uri, CodeActionInfo core) {
    CodeAction action = new CodeAction(core.getTitle());
    if (core.getKind() != null && !core.getKind().isBlank()) {
      action.setKind(core.getKind());
    }
    action.setIsPreferred(core.isPreferred());
    Map<String, List<org.eclipse.lsp4j.TextEdit>> changes = new LinkedHashMap<>();
    changes.put(
        uri, core.getEdits().stream().map(LspTypeConverter::toLsp).collect(Collectors.toList()));
    WorkspaceEdit workspaceEdit = new WorkspaceEdit();
    workspaceEdit.setChanges(changes);
    action.setEdit(workspaceEdit);
    return action;
  }

  public static List<Diagnostic> toCoreDiagnostics(List<org.eclipse.lsp4j.Diagnostic> diagnostics) {
    return diagnostics == null
        ? List.of()
        : diagnostics.stream().map(LspTypeConverter::toCore).toList();
  }

  // -------------------------------------------------------------------------
  // Batch conversions
  // -------------------------------------------------------------------------

  public static List<org.eclipse.lsp4j.Diagnostic> toLspDiagnostics(
      List<? extends Diagnostic> diagnostics) {
    return diagnostics.stream().map(LspTypeConverter::toLsp).collect(Collectors.toList());
  }

  public static List<org.eclipse.lsp4j.CompletionItem> toLspCompletionItems(
      List<? extends se.alipsa.jvmpls.core.model.CompletionItem> items) {
    return items.stream().map(LspTypeConverter::toLsp).collect(Collectors.toList());
  }

  private static SymbolKind toLsp(SymbolInfo.Kind kind) {
    return switch (kind) {
      case PACKAGE -> SymbolKind.Package;
      case CLASS -> SymbolKind.Class;
      case INTERFACE -> SymbolKind.Interface;
      case ENUM -> SymbolKind.Enum;
      case METHOD -> SymbolKind.Method;
      case CONSTRUCTOR -> SymbolKind.Constructor;
      case FIELD -> SymbolKind.Field;
      case ANNOTATION -> SymbolKind.Class;
    };
  }

  private static String symbolName(SymbolInfo core) {
    String fqn = core.getFqName();
    return switch (core.getKind()) {
      case FIELD -> fqn.substring(fqn.lastIndexOf('.') + 1);
      case METHOD -> {
        int hash = fqn.lastIndexOf('#');
        int open = fqn.indexOf('(', hash + 1);
        yield open < 0 ? fqn.substring(hash + 1) : fqn.substring(hash + 1, open);
      }
      case CONSTRUCTOR -> {
        String owner = core.getContainerFqName();
        int lastDot = owner.lastIndexOf('.');
        yield lastDot < 0 ? owner : owner.substring(lastDot + 1);
      }
      default -> {
        int lastDot = fqn.lastIndexOf('.');
        yield lastDot < 0 ? fqn : fqn.substring(lastDot + 1);
      }
    };
  }
}
