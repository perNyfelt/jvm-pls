module se.alipsa.jvmpls.java.tests {
  requires se.alipsa.jvmpls.core;   // CoreServer/CoreFacade, model, etc.
  requires se.alipsa.jvmpls.java;   // The plugin under test (provides JvmLangPlugin)
  requires org.junit.jupiter.api;   // JUnit 5 API

  // JUnit reflects into your test classes
  opens test.alipsa.jvmpls.java to org.junit.platform.commons;
}
