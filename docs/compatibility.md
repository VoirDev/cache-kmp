# Compatibility matrix

The following matrix is the validated build baseline for `1.0.0`:

| Surface | Validated version or target |
| --- | --- |
| Kotlin | 2.4.10 |
| Gradle wrapper | 9.5.0 |
| Android Gradle Plugin | 9.3.0 |
| kotlinx.coroutines | 1.11.0 |
| kotlinx.serialization JSON | 1.11.0 |
| Room | 3.0.1 |
| AndroidX SQLite | 2.7.0 |
| Android | min SDK 23, compile SDK 37 |
| iOS device | `iosArm64` |
| iOS simulator | `iosSimulatorArm64` |
| JVM | Java 21 toolchain and bytecode target |

`cache-core` and `cache-testing` do not expose Room or SQLite. `cache-room` uses the bundled SQLite
driver for Android, JVM, and iOS production stores; the Android host smoke test uses the framework
driver because host JVMs cannot load Android ABI libraries.

CI treats compilation, JVM/Android-host/iOS-simulator tests, ABI comparison, local Maven publication,
published consumer tests, and memory dependency isolation as release gates. Other Kotlin, Gradle,
JDK, Android, Apple, and dependency combinations are not guaranteed until added to this matrix.

## Version compatibility

Stable releases follow Semantic Versioning. Starting with `1.0.0`, incompatible stable API or
behavior changes require a major version, compatible features use a minor version, and fixes use a
patch version. APIs marked with `ExperimentalCacheStoreApi` may change while storage adapters
stabilize.

Kotlin binary compatibility does not guarantee persistent-data compatibility. Applications must
also version serialized payloads and apply any required Room schema migrations; see
[Migrations](migrations.md).
