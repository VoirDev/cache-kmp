# Changelog

All notable changes are documented here. The project follows Semantic Versioning and keeps the
format compatible with Keep a Changelog.

## [Unreleased]

## [1.0.0] - 2026-08-11

### Added

- Initial Kotlin Multiplatform cache engine for Android, iOS, and JVM.
- Typed memory caching, stale-while-refresh behavior, single-flight loading, bounded LRU storage,
  mutation and invalidation operations, diagnostics, and explicit lifecycle ownership.
- Optional Room 3 persistence with namespace, partition, global clearing, and pruning.
- Optional deterministic kotlinx.serialization JSON codec with explicit payload migrations.
- Reusable deterministic clock and in-memory persistent-store fixtures in `cache-testing`.
- ABI validation, source and Dokka JAR publication, consumer samples, and release CI checks.

[Unreleased]: https://github.com/VoirDev/cache-kmp/commits/main
[1.0.0]: https://github.com/VoirDev/cache-kmp/releases/tag/v1.0.0
