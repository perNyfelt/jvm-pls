module se.alipsa.jvmpls.core.tests {
  requires se.alipsa.jvmpls.core;
  requires org.junit.jupiter.api;

  opens test.alipsa.jvmpls.core.server to org.junit.platform.commons;

  // Make the test-only plugin visible to ServiceLoader at test runtime
  provides se.alipsa.jvmpls.core.JvmLangPlugin
      with test.alipsa.jvmpls.plugins.TrivialJavaPlugin;
}
