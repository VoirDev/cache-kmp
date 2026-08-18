# CI/CD pipelines

## Pull request verification

Every pull request into `main` runs `test.yaml` on macOS so JVM, Android host, iOS device
compilation, and iOS simulator tests are all covered from one publishing host. Add `[skip verify]` to
the pull request description to skip checks intentionally. Updating the description reruns the
workflow with the new behavior.

Verification performs:

- a clean build and every configured platform test;
- committed Kotlin ABI comparison;
- publication of every KMP variant to a workflow-local Maven repository;
- external memory, persistent-value, and file consumer builds from that Maven repository, each with
  only the `dev.voir:cache` dependency.

## Release flow

1. Run the `Release` workflow manually with `prepare-release-pr` mode. Leave `version` empty to bump
   the patch component in `VERSION`, or provide an explicit `X.Y.Z` version.
2. The workflow creates or updates `release/vX.Y.Z` and opens a release pull request with generated
   notes listing merged pull requests since the previous release.
3. Review and merge the release pull request into `main` after its normal verification succeeds.
4. The merge starts the publish job, which validates `VERSION` and confirms that `vX.Y.Z` does not
   exist.
5. It rebuilds, tests, compares ABI, locally publishes the exact release version, and rebuilds the
   external consumers against that version.
6. It publishes `dev.voir:cache` to Maven Central, creates and pushes tag `vX.Y.Z`, and creates a
   GitHub Release using the release pull request body.

For recovery or an initial release, run the same workflow with `publish-current` mode. This publishes
the version already present on `main` and generates notes from merged pull requests. Maven Central is
the only remote artifact repository used by the release workflow. Published Maven versions are
immutable, so the current version and tag must not have been published already.

## Required repository configuration

Repository secrets:

- `CENTRAL_USERNAME`: username from a Maven Central Portal user token.
- `CENTRAL_PASSWORD`: password from the same Maven Central Portal user token.
- `MAVEN_GPG_PRIVATE_KEY`: ASCII-armored GPG private key used to sign every publication.
- `MAVEN_GPG_PASSPHRASE`: passphrase for `MAVEN_GPG_PRIVATE_KEY` when the key is protected.
- `RELEASE_BOT_TOKEN`: token used to push a release branch and open a pull request whose checks can
  run normally.

The publish job deliberately has no credential fallback. Central credentials and the signing key
are passed through Gradle's supported environment properties and are never written to project files.

## Version source

The repository version lives in the root `VERSION` file. Gradle reads that file for normal builds.
The publish job also passes the detected value as `-PreleaseVersion` so verification, publication,
external consumer tests, the Git tag, and the GitHub Release use one exact version.
