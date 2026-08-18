# Published consumer examples

These JVM tests resolve `dev.voir:cache` from a Maven repository instead of depending on the
repository's `:cache` project:

- `memory-consumer` uses a reusable typed memory scope;
- `persistent-consumer` restores a Kotlinx-serialized value after manager recreation;
- `file-consumer` restores and leases streamed content after manager recreation.

Each consumer declares exactly one library dependency:

```kotlin
implementation("dev.voir:cache:1.0.0")
```

After publishing locally from the repository root, run:

```shell
./gradlew -p samples/consumer \
  -PcacheRepository="$PWD/build/local-maven" \
  -PcacheVersion=1.0.0 \
  check --refresh-dependencies
```
