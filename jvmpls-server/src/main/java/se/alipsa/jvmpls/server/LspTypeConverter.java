package se.alipsa.jvmpls.server;

import java.util.List;
import java.util.stream.Collectors;

import se.alipsa.jvmpls.core.model.Diagnostic;

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
}
