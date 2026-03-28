package se.alipsa.jvmpls.core;

import java.util.List;

/**
 * ServiceLoader entry point for registering lazy external symbol providers.
 */
public interface SymbolProviderFactory {

  String id();

  List<SymbolProvider> createProviders(SymbolProviderContext context);
}
