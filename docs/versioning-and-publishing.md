# Versioning and publishing

## Coordinates

`dev.voir:cache-kmp` names the library family. Consumers select focused artifacts:

| Artifact | Purpose |
| --- | --- |
| `dev.voir:cache-core` | Public contracts, engine, memory tier, and persistence SPI |
| `dev.voir:cache-room` | Optional Room-backed persistent store |
| `dev.voir:cache-codec-kotlinx` | Optional kotlinx.serialization JSON codec |
| `dev.voir:cache-testing` | Reusable test clock and persistent-store fake |

Kotlin Multiplatform publishing also creates target-specific Maven artifacts selected automatically
by Gradle metadata. Every publication includes sources, Dokka HTML as a `javadoc` JAR, license,
developer, SCM, and project metadata.

The Vanniktech Maven Publish plugin configures each KMP module, signs release artifacts, uploads one
combined deployment through Maven Central's Central Portal, and releases it after validation.

## Semantic versioning

- Before `1.0.0`, a minor version may change experimental APIs; patch versions remain compatible.
- From `1.0.0`, incompatible stable public API or behavior requires a major version, compatible
  features use a minor version, and compatible fixes use a patch version.
- The `ExperimentalCacheStoreApi` SPI may evolve while storage adapters stabilize. Stable domain APIs
  are not covered by that exception.
- Stored payload and Room schema compatibility are reviewed separately from Kotlin binary ABI. A
  source-compatible API change can still require payload or database migration.

Each module commits Kotlin ABI dumps under `api/`. `check` runs `checkKotlinAbi`; any public ABI
change fails until reviewers inspect the diff and deliberately run `./gradlew updateKotlinAbi`.

## Local release verification

```shell
./gradlew clean build checkKotlinAbi publishAllPublicationsToLocalReleaseRepository
./gradlew -p samples/consumer \
  -PcacheRepository="$PWD/build/local-maven" \
  build
```

Run the `Release` GitHub Actions workflow in `prepare-release-pr` mode to bump the patch version or
provide an explicit release version. Merging the generated release pull request publishes that
version to Maven Central, then creates its tag and GitHub Release. The `publish-current` mode can
publish the version already present on `main` when recovery or an initial release requires it.

To publish the current checkout to Maven Central manually after local verification:

```shell
./gradlew publishToMavenCentral --no-configuration-cache
```

Maven Central requires a Portal user token and GPG signing key. Supply them as Gradle properties
`mavenCentralUsername`, `mavenCentralPassword`, `signingInMemoryKey`, and
`signingInMemoryKeyPassword`, or through the equivalent `ORG_GRADLE_PROJECT_*` environment variables
used by the release workflow. Keep every credential outside source control.
