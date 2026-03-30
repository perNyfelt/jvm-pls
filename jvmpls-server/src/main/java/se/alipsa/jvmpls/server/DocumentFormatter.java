package se.alipsa.jvmpls.server;

import java.util.List;

import se.alipsa.jvmpls.core.model.TextEdit;

public interface DocumentFormatter {
  List<TextEdit> format(String uri, String text);

  static DocumentFormatter disabled() {
    return (uri, text) -> List.of();
  }
}
