# ADR 0001: Use Room 3 for the persistent cache store

- Status: accepted
- Date: 2026-08-11

## Context

`cache-room` is a new Kotlin Multiplatform artifact and database. It does not need to share a Room
database or generated types with the reference application, which currently uses Room 2.

Room 3 is Kotlin- and coroutine-first, requires KSP, and keeps its API in the separate
`androidx.room3` package. The official Kotlin Multiplatform setup uses Room 3.0.1 with SQLite 2.7.0
and recommends the bundled SQLite driver for consistent behavior across targets. Kotlin's KSP
quick-start pairs Kotlin 2.4.10 with KSP 2.3.10.

## Decision

Use these stable versions:

- Room `3.0.1` (`androidx.room3`)
- SQLite `2.7.0` with `BundledSQLiteDriver`
- KSP `2.3.10`

Room entities, DAOs, the database, and its generated constructor remain internal to `cache-room`.
The public platform factories return only `PersistentCacheStore`, so this choice does not add Room
types to `cache-core` or the normal consumer API.

## Consequences

- All DAO operations are suspending and use Room's coroutine configuration.
- Each supported target receives KSP processing and a path/context-specific builder.
- Schema JSON is committed for migration review.
- Applications can keep an unrelated Room 2 database because Room 3 uses distinct artifacts and
  packages.
- Version 1 supports one database owner per application process; non-Android targets do not provide
  Room multi-instance invalidation.
