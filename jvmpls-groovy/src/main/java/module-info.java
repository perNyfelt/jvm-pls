module se.alipsa.jvmpls.groovy {
  requires se.alipsa.jvmpls.core;
  requires org.apache.groovy; // Groovy 4 automatic module

  provides se.alipsa.jvmpls.core.JvmLangPlugin
      with se.alipsa.jvmpls.groovy.GroovyPlugin;
}
