package se.alipsa.jvmpls.classpath;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class BinaryTypeReader {

  private final ConcurrentMap<String, BinaryTypeDetails> cache = new ConcurrentHashMap<>();

  public BinaryTypeDetails read(String resourceUri) {
    return cache.computeIfAbsent(resourceUri, this::readUncached);
  }

  private BinaryTypeDetails readUncached(String resourceUri) {
    try (InputStream inputStream = URI.create(resourceUri).toURL().openStream()) {
      ClassReader reader = new ClassReader(inputStream);
      ReaderVisitor visitor = new ReaderVisitor();
      reader.accept(visitor, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
      return new BinaryTypeDetails(
          visitor.signature == null ? "" : visitor.signature,
          visitor.modifiers,
          List.of());
    } catch (IOException | IllegalArgumentException e) {
      return new BinaryTypeDetails("", Set.of(), List.of());
    }
  }

  private static final class ReaderVisitor extends ClassVisitor {

    private String signature;
    private Set<String> modifiers = Set.of();

    private ReaderVisitor() {
      super(Opcodes.ASM9);
    }

    @Override
    public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
      this.signature = signature;
      this.modifiers = toModifiers(access);
    }

    private static Set<String> toModifiers(int access) {
      Set<String> out = new LinkedHashSet<>();
      if ((access & Opcodes.ACC_PUBLIC) != 0) out.add("public");
      if ((access & Opcodes.ACC_PROTECTED) != 0) out.add("protected");
      if ((access & Opcodes.ACC_PRIVATE) != 0) out.add("private");
      if ((access & Opcodes.ACC_ABSTRACT) != 0) out.add("abstract");
      if ((access & Opcodes.ACC_FINAL) != 0) out.add("final");
      if ((access & Opcodes.ACC_STATIC) != 0) out.add("static");
      if ((access & Opcodes.ACC_INTERFACE) != 0) out.add("interface");
      if ((access & Opcodes.ACC_ENUM) != 0) out.add("enum");
      if ((access & Opcodes.ACC_ANNOTATION) != 0) out.add("annotation");
      return Set.copyOf(out);
    }
  }
}
