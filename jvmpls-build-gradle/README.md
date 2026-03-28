# jvmpls-build-gradle

`jvmpls-build-gradle` is the Gradle workspace resolver for `jvm-pls`.

## Module Purpose

This module implements `BuildToolPlugin` for Gradle workspaces. It detects Gradle build files, uses the Gradle Tooling API to inspect the workspace, and extracts source roots, output roots, external dependencies, and the target JDK for `jvmpls-server`.

## Typical Usage

This module is normally consumed indirectly through `jvmpls-server`. When it is on the runtime classpath, `BuildToolRegistry` discovers it through `ServiceLoader` and uses it for Gradle workspaces.

Phase 3 assumes either:

- a valid Gradle wrapper in the workspace, or
- a local Gradle installation available to the Tooling API

The server does not bundle a Gradle distribution.
