package se.alipsa.jvmpls.classpath;

import java.util.List;
import java.util.Set;

import se.alipsa.jvmpls.core.model.SymbolInfo;

record BinaryMemberDetails(
    SymbolInfo.Kind kind,
    String name,
    String descriptor,
    String signature,
    Set<String> modifiers,
    List<String> exceptions) {}
