package se.alipsa.jvmpls.classpath;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import se.alipsa.jvmpls.core.model.SymbolInfo;

public final class BinaryTypeReader {
  private static final Logger LOG = Logger.getLogger(BinaryTypeReader.class.getName());

  private final ConcurrentMap<String, BinaryTypeDetails> cache = new ConcurrentHashMap<>();

  public BinaryTypeDetails read(String resourceUri) {
    BinaryTypeDetails cached = cache.get(resourceUri);
    if (cached != null) {
      return cached;
    }
    BinaryTypeDetails details = readUncached(resourceUri);
    if (!details.isEmpty()) {
      cache.put(resourceUri, details);
    }
    return details;
  }

  private BinaryTypeDetails readUncached(String resourceUri) {
    try (InputStream inputStream = URI.create(resourceUri).toURL().openStream()) {
      ClassReader reader = new ClassReader(inputStream);
      ReaderVisitor visitor = new ReaderVisitor();
      reader.accept(
          visitor, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
      return new BinaryTypeDetails(
          visitor.signature == null ? "" : visitor.signature,
          visitor.modifiers,
          List.of(),
          List.copyOf(visitor.members));
    } catch (IOException | IllegalArgumentException e) {
      LOG.log(Level.WARNING, "Failed to read binary type metadata from " + resourceUri, e);
      return BinaryTypeDetails.empty();
    }
  }

  private static final class ReaderVisitor extends ClassVisitor {

    private String signature;
    private Set<String> modifiers = Set.of();
    private final List<BinaryMemberDetails> members = new ArrayList<>();

    private ReaderVisitor() {
      super(Opcodes.ASM9);
    }

    @Override
    public void visit(
        int version,
        int access,
        String name,
        String signature,
        String superName,
        String[] interfaces) {
      this.signature = signature;
      this.modifiers = toModifiers(access);
    }

    @Override
    public FieldVisitor visitField(
        int access, String name, String descriptor, String signature, Object value) {
      members.add(
          new BinaryMemberDetails(
              SymbolInfo.Kind.FIELD,
              name,
              descriptor,
              signature == null ? "" : signature,
              toModifiers(access),
              List.of()));
      return null;
    }

    @Override
    public MethodVisitor visitMethod(
        int access, String name, String descriptor, String signature, String[] exceptions) {
      if (!"<clinit>".equals(name)) {
        members.add(
            new BinaryMemberDetails(
                SymbolInfo.Kind.METHOD,
                name,
                descriptor,
                signature == null ? "" : signature,
                toModifiers(access),
                exceptions == null
                    ? List.of()
                    : java.util.Arrays.stream(exceptions)
                        .map(internalName -> internalName.replace('/', '.'))
                        .toList()));
      }
      return null;
    }

    private static Set<String> toModifiers(int access) {
      Set<String> out = new LinkedHashSet<>();
      if ((access & Opcodes.ACC_PUBLIC) != 0) out.add("public");
      if ((access & Opcodes.ACC_PROTECTED) != 0) out.add("protected");
      if ((access & Opcodes.ACC_PRIVATE) != 0) out.add("private");
      if ((access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED | Opcodes.ACC_PRIVATE)) == 0)
        out.add("package-private");
      if ((access & Opcodes.ACC_ABSTRACT) != 0) out.add("abstract");
      if ((access & Opcodes.ACC_FINAL) != 0) out.add("final");
      if ((access & Opcodes.ACC_STATIC) != 0) out.add("static");
      if ((access & Opcodes.ACC_SYNCHRONIZED) != 0) out.add("synchronized");
      if ((access & Opcodes.ACC_INTERFACE) != 0) out.add("interface");
      if ((access & Opcodes.ACC_ENUM) != 0) out.add("enum");
      if ((access & Opcodes.ACC_ANNOTATION) != 0) out.add("annotation");
      return Set.copyOf(out);
    }
  }
}
