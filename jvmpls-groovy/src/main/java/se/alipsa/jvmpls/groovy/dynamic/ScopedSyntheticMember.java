package se.alipsa.jvmpls.groovy.dynamic;

import java.util.Objects;

import se.alipsa.jvmpls.core.model.Position;
import se.alipsa.jvmpls.core.model.Range;
import se.alipsa.jvmpls.core.model.SymbolInfo;

public record ScopedSyntheticMember(String targetTypeFqn, Range scope, SymbolInfo symbol) {

  public ScopedSyntheticMember {
    Objects.requireNonNull(targetTypeFqn, "targetTypeFqn");
    Objects.requireNonNull(scope, "scope");
    Objects.requireNonNull(symbol, "symbol");
  }

  public boolean isVisibleAt(Position position) {
    if (position == null || scope == null) {
      return false;
    }
    if (position.line < scope.start.line || position.line > scope.end.line) {
      return false;
    }
    if (position.line == scope.start.line && position.column < scope.start.column) {
      return false;
    }
    return position.line != scope.end.line || position.column <= scope.end.column;
  }
}
