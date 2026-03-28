# jvmpls-build

`jvmpls-build` defines the build-system plugin API for `jvm-pls`.

## Module Purpose

This module contains the shared contracts used to discover and resolve workspace build state:

- `BuildToolPlugin`
- `BuildToolRegistry`
- `BuildModel`
- `BuildModule`

It is the abstraction layer between the language server workspace lifecycle and concrete Maven or Gradle implementations.

## Typical Usage

Most consumers do not use this module directly. It is used by `jvmpls-server`, which loads `BuildToolPlugin` implementations through `ServiceLoader` and converts the resulting `BuildModel` into classpath and JDK configuration for `jvmpls-core`.

Use this module directly only if you are embedding your own workspace bootstrap around `jvmpls-core`.
