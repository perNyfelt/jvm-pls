package se.alipsa.jvmpls.classpath;

import java.util.List;
import java.util.Set;

record BinaryTypeDetails(String signature,
                         Set<String> modifiers,
                         List<String> typeParameters,
                         List<BinaryMemberDetails> members) {
}
