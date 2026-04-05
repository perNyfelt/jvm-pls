# jvm-pls release history

## v1.0.0 - In progress

### Release Strategy

`jvm-pls` uses a manual release flow for `v1.0.0`:

1. update versions with `versions-maven-plugin`
2. verify the release candidate from a clean workspace
3. create and push a git tag
4. publish with the Maven `release` profile
5. bump to the next `-SNAPSHOT` version

This repository does not use `maven-release-plugin` for `v1.0.0`.

### Published Modules

Published to Maven Central:

- `jvmpls-core`
- `jvmpls-classpath`
- `jvmpls-build`
- `jvmpls-build-maven`
- `jvmpls-build-gradle`
- `jvmpls-java`
- `jvmpls-groovy`
- `jvmpls-server`

Not published:

- `jvmpls-it`
- `jvmpls-bench`

### Release Prerequisites

- Java 21+
- Maven 3.9.9+
- a clean git worktree
- credentials/configuration for the `central` publishing server used by `central-publishing-maven-plugin`
- GPG signing configured for `maven-gpg-plugin`

### Release Verification Matrix

Run these commands before publishing:

```bash
mvn -q -DskipTests install
mvn -q test
mvn -pl jvmpls-it verify
mvn -pl jvmpls-bench -Pbench test
mvn -pl jvmpls-server package -DskipTests
mvn -Prelease verify
```

Verification expectations:

- unit tests pass
- integration tests pass
- benchmark profile completes successfully and results are recorded for the release candidate
- `jvmpls-server/target/jvmpls-server-<version>-standalone.jar` is created
- SpotBugs fails the `release` build on findings

### Standalone Smoke Checks

After packaging, verify the CLI manually:

```bash
java -jar jvmpls-server/target/jvmpls-server-<version>-standalone.jar --help
java -jar jvmpls-server/target/jvmpls-server-<version>-standalone.jar --version
```

Expected behavior:

- `--help` prints usage with `--stdio`, `--version`, and `--help`
- `--version` prints the current project version

### Release Procedure

1. Set the release version:

```bash
mvn versions:set -DnewVersion=1.0.0
```

2. Commit the release version change:

```bash
git add pom.xml */pom.xml
git commit -m "Prepare release 1.0.0"
```

3. Re-run the release verification matrix against the release version.

4. Tag the release:

```bash
git tag v1.0.0
git push origin main --tags
```

5. Publish to Maven Central with the release profile:

```bash
mvn -Prelease deploy
```

6. Confirm that Central publication completes and that the published module list is correct.

7. Create a GitHub release for `v1.0.0` and attach:
   - `jvmpls-server/target/jvmpls-server-1.0.0-standalone.jar`

8. Bump back to the next snapshot:

```bash
mvn versions:set -DnewVersion=1.0.1-SNAPSHOT
git add pom.xml */pom.xml
git commit -m "Resume development after 1.0.0"
git push origin main
```

### Post-Release Checks

- verify the Maven Central coordinates resolve correctly
- verify the GitHub release contains the standalone JAR
- verify the standalone JAR starts and responds to `--help` and `--version`
