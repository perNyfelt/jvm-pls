package se.alipsa.jvmpls.groovy.transforms;

import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.ClassNode;
import se.alipsa.jvmpls.core.CoreQuery;
import se.alipsa.jvmpls.core.model.Location;
import se.alipsa.jvmpls.core.types.JvmType;

import java.util.function.Function;

public record TransformContext(String fileUri,
                               String ownerFqn,
                               CoreQuery core,
                               Function<ClassNode, JvmType> typeOf,
                               Function<ASTNode, Location> locationOf) {

  public String packageName() {
    int lastDot = ownerFqn.lastIndexOf('.');
    return lastDot < 0 ? "" : ownerFqn.substring(0, lastDot);
  }

  public String simpleName() {
    int lastDot = ownerFqn.lastIndexOf('.');
    return lastDot < 0 ? ownerFqn : ownerFqn.substring(lastDot + 1);
  }
}
