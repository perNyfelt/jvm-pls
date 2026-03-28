# jvmpls-it

`jvmpls-it` contains the cross-module integration test suite for `jvm-pls`.

## Module Purpose

This module verifies behavior that only shows up when multiple modules work together, for example:

- Java and Groovy plugins resolving shared symbols
- external JDK and classpath symbol resolution
- end-to-end behavior across `jvmpls-core`, plugins, and provider modules

It is a test module, not a runtime dependency.

## How To Use It

Run the integration suite with Maven Failsafe:

```bash
mvn -pl jvmpls-it -am verify
```

This module intentionally skips Surefire unit tests and runs `*IT.java` and `*ITCase.java` during `integration-test` and `verify`.

## When To Update It

Add or extend tests here when a feature crosses module boundaries and cannot be validated well inside a single module's unit tests.
