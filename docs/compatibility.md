# Compatibility

The validated baseline for `1.0.0` is:

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

`dev.voir:cache` is the only consumer artifact. Room and SQLite are internal runtime
dependencies used for persistent values and file metadata; consumers do not configure their APIs.

CI validates JVM and Android host tests, iOS simulator tests, iOS device compilation, Kotlin ABI,
Dokka, every KMP publication variant, and three standalone consumers resolving the local Maven
artifact. Other toolchain or platform combinations are not guaranteed until added to this matrix.

Custom keys, paged queries, and values require a Kotlinx serializer. Apply a Kotlin serialization plugin compatible
with the Kotlin compiler used by the application.

Kotlin binary compatibility and stored-data compatibility are separate. A serializable model can
remain source-compatible while changing its durable JSON representation. See
[Serialized-data changes](migrations.md).
