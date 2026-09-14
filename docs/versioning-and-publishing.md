# Versioning and publishing

## Coordinate

Consumers add exactly one Kotlin Multiplatform artifact:

```kotlin
implementation("dev.voir:cache:1.0.0")
```

Gradle metadata selects the Android, iOS, JVM, or common variant automatically. Every publication
includes sources, Dokka HTML as a `javadoc` JAR, license, developer, SCM, and project metadata.

The repository version lives in [VERSION](../VERSION).

## ABI

The committed ABI dumps are:

- `cache/api/cache.klib.api` for Kotlin/Native and common metadata;
- `cache/api/jvm/cache.api` for JVM.

Only the `dev.voir.cache` facade is public. Engine, codec, Room, filesystem, persistence, policy,
diagnostic, and storage contracts are internal. Any intentional public change requires reviewing
the ABI diff and running:

```shell
./gradlew :cache:updateKotlinAbi
```

## Local verification

```shell
./gradlew clean build checkKotlinAbi \
  publishAllPublicationsToLocalReleaseRepository

./gradlew -p samples/consumer \
  -PcacheRepository="$PWD/build/local-maven" \
  -PcacheVersion=1.0.0 \
  check --refresh-dependencies
```

The second build is intentionally separate. It proves real consumers require only
`dev.voir:cache` and cannot access source-internal implementation APIs.

## Release workflow

The `Release` GitHub Actions workflow prepares a version PR or publishes the current `VERSION`.
After clean build, tests, ABI validation, local publication, and consumer verification pass, the
workflow publishes the single KMP module to Maven Central, pushes `vX.Y.Z`, and creates a GitHub
Release.

To publish manually after local verification:

```shell
./gradlew publishToMavenCentral --no-configuration-cache
```

Maven Central requires a Portal user token and GPG signing key. Supply them through the Gradle
properties documented in the release workflow and keep every credential outside source control.
