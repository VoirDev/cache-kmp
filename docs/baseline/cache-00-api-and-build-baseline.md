# CACHE-00 API and build baseline

Status: frozen on 2026-08-10 from `/Users/garybezruchko/Projects/voir/sonars-app`.

## Migration status

All prototype declarations listed below are treated as experimental migration inputs. They are not
part of the compatibility surface of `dev.voir:cache-kmp`, and consumers must not add new usages.
The new artifacts will establish their supported public API under explicit API mode in CACHE-01.

The duplicated `dev.voir.sonars.data.cache` facade is internal migration code. It will not be copied
into this library and must not dictate package names or dependency direction.

## Prototype public declarations

| Package | Declaration | Public surface |
| --- | --- | --- |
| `dev.voir.cache.controller` | `CachedResource<K, V, E>` | `observe`, `current`, `restore`, `ensureFresh`, `refresh`, `invalidate`, `remove`, `updateData`, `clear` |
| `dev.voir.cache.controller` | `CachedResourceFactory` | constructor and `create` |
| `dev.voir.cache.model` | `CacheKey` | `stableValue` |
| `dev.voir.cache.model` | `CachePolicy` | `timeToLive`, `expiresAt`, `expiring`, `session` |
| `dev.voir.cache.model` | `CacheMetadata` | key and validation/expiration/invalidation timestamps, `isStaleAt` |
| `dev.voir.cache.model` | `CacheFreshness` | `FRESH`, `STALE` |
| `dev.voir.cache.model` | `CacheSnapshot<T, E>` | data, freshness, refresh activity/error, derived initial states |
| `dev.voir.cache.model` | `CacheResult<T, E>` | sealed result root |
| `dev.voir.cache.model` | `CacheSuccess<T>` | successful data |
| `dev.voir.cache.model` | `CacheFailure<E>` | application-defined error |
| `dev.voir.cache.runtime` | `CacheRuntime` | owned scope, active state, idempotent close |
| `dev.voir.cache.time` | `CacheClock` | `now` |
| `dev.voir.cache.time` | `SystemCacheClock` | system-clock implementation |

Prototype controllers, stores, stored-resource state, and single-flight bookkeeping are internal.

## Prototype module dependencies

### `:cache`

- Production: Kotlin standard library and `kotlinx-coroutines-core`.
- Tests: `kotlin-test` and `kotlinx-coroutines-test`.
- No Room, SQLite, serialization format, DI, logging, networking, or Sonars dependency.

### `:data` persistence integration

- Project dependency on `:cache`.
- Room runtime and compiler, bundled SQLite, kotlinx.serialization JSON, coroutines, and Koin.
- Room compiler configured through KSP for Android, JVM, iOS arm64, and iOS simulator arm64.
- Contains application-specific entities and duplicated cache contracts; neither is retained.

## Prototype targets and verification tasks

Both modules target Android, JVM, `iosArm64`, and `iosSimulatorArm64` with JDK 21. The target task
baseline to preserve while replacing internals is:

| Concern | `:cache` task | `:data` task |
| --- | --- | --- |
| Android main compilation | `:cache:compileAndroidMain` | `:data:compileAndroidMain` |
| JVM main compilation | `:cache:compileKotlinJvm` | `:data:compileKotlinJvm` |
| iOS device compilation | `:cache:compileKotlinIosArm64` | `:data:compileKotlinIosArm64` |
| iOS simulator compilation | `:cache:compileKotlinIosSimulatorArm64` | `:data:compileKotlinIosSimulatorArm64` |
| Android host tests | `:cache:testAndroidHostTest` | `:data:testAndroidHostTest` |
| JVM tests | `:cache:jvmTest` | `:data:jvmTest` |
| iOS simulator tests | `:cache:iosSimulatorArm64Test` | `:data:iosSimulatorArm64Test` |
| Aggregate verification | `:cache:build` | `:data:build` |

The new repository currently mirrors the same four targets in each cache artifact. Room compiler
tasks remain deferred until the CACHE-05 compatibility decision.

