package se.alipsa.jvmpls.classpath;

import java.util.List;
import java.util.Set;

record BinaryTypeDetails(String signature,
                         Set<String> modifiers,
                         List<String> typeParameters,
                         List<BinaryMemberDetails> members) {

  static BinaryTypeDetails empty() {
    return new BinaryTypeDetails("", Set.of(), List.of(), List.of());
  }

  boolean isEmpty() {
    return signature.isBlank() && modifiers.isEmpty() && typeParameters.isEmpty() && members.isEmpty();
  }
}
