# jvmpls-build-maven

`jvmpls-build-maven` is the Maven workspace resolver for `jvm-pls`.

## Module Purpose

This module implements `BuildToolPlugin` for Maven workspaces. It detects `pom.xml`, resolves the effective project model, extracts source/output roots, and builds a workspace classpath for `jvmpls-server`.

## Typical Usage

This module is normally consumed indirectly through `jvmpls-server`. When it is on the runtime classpath, `BuildToolRegistry` discovers it through `ServiceLoader` and uses it for Maven workspaces.

It uses the cached Alipsa Maven utility library to parse effective POM models and resolve dependency artifacts.
