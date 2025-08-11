module se.alipsa.jvmpls.it.tests {
  requires se.alipsa.jvmpls.core;
  requires se.alipsa.jvmpls.java;
  requires se.alipsa.jvmpls.groovy;
  requires org.junit.jupiter.api;

  // JUnit reflects into your tests' package
  opens test.alipsa.jvmpls.it to org.junit.platform.commons;
}
