package se.alipsa.jvmpls.groovy.transforms;

import java.util.Objects;
import java.util.function.Function;

import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.ClassNode;

import se.alipsa.jvmpls.core.CoreQuery;
import se.alipsa.jvmpls.core.model.Location;
import se.alipsa.jvmpls.core.types.JvmType;

public record TransformContext(
    String fileUri,
    String ownerFqn,
    CoreQuery core,
    Function<ClassNode, JvmType> typeOf,
    Function<ASTNode, Location> locationOf) {

  public TransformContext {
    Objects.requireNonNull(fileUri, "fileUri");
    Objects.requireNonNull(ownerFqn, "ownerFqn");
    Objects.requireNonNull(core, "core");
    Objects.requireNonNull(typeOf, "typeOf");
    Objects.requireNonNull(locationOf, "locationOf");
  }

  public String packageName() {
    int lastDot = ownerFqn.lastIndexOf('.');
    return lastDot < 0 ? "" : ownerFqn.substring(0, lastDot);
  }

  public String simpleName() {
    int lastDot = ownerFqn.lastIndexOf('.');
    return lastDot < 0 ? ownerFqn : ownerFqn.substring(lastDot + 1);
  }
}
