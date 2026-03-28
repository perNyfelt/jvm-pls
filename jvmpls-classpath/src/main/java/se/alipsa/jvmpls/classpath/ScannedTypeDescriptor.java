package se.alipsa.jvmpls.classpath;

import se.alipsa.jvmpls.core.model.SymbolInfo;

record ScannedTypeDescriptor(String fqName,
                             String packageName,
                             String containerFqName,
                             SymbolInfo.Kind kind,
                             String resourceUri,
                             String superclassFqName,
                             java.util.List<String> interfaceFqNames) {

  String simpleName() {
    int lastDot = fqName.lastIndexOf('.');
    String name = lastDot < 0 ? fqName : fqName.substring(lastDot + 1);
    int nestedSep = name.lastIndexOf('$');
    return nestedSep < 0 ? name : name.substring(nestedSep + 1);
  }
}
