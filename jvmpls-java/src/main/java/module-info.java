module se.alipsa.jvmpls.java {
  requires se.alipsa.jvmpls.core;
  requires jdk.compiler; // JavaCompiler, Trees, Tree API

  provides se.alipsa.jvmpls.core.JvmLangPlugin
      with se.alipsa.jvmpls.java.JavaPlugin;
}
