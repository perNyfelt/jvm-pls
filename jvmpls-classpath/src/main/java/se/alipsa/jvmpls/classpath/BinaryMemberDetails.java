package se.alipsa.jvmpls.classpath;

import se.alipsa.jvmpls.core.model.SymbolInfo;

import java.util.List;
import java.util.Set;

record BinaryMemberDetails(SymbolInfo.Kind kind,
                           String name,
                           String descriptor,
                           String signature,
                           Set<String> modifiers,
                           List<String> exceptions) {
}
