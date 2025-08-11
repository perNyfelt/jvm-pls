module se.alipsa.jvmpls.groovy.tests {
  requires se.alipsa.jvmpls.core;
  requires se.alipsa.jvmpls.groovy;
  requires org.junit.jupiter.api;

  opens test.alipsa.jvmpls.groovy to org.junit.platform.commons;
}
