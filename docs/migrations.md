# Migrations

## Operation result handling

`CacheResult` includes `CacheDiscarded` for a source completion rejected by a newer mutation or
clear. Consumers with an exhaustive `when` must add a branch for it:

```kotlin
when (val result = resource.get(key)) {
    is CacheSuccess -> use(result.value)
    is CacheFailure -> handle(result.error, result.staleValue)
    is CacheDiscarded -> render(result.currentSnapshot)
}
```

Only `CacheSuccess` represents a committed source value. `CacheDiscarded.currentSnapshot` is the
authoritative winning state; a rejected source value is intentionally unavailable.

## JSON payload evolution

Every persistent codec writes a positive `currentVersion`. When the serialized shape changes:

1. Increment `currentVersion`.
2. Keep an explicit `JsonPayloadMigration` for each still-supported old version.
3. Test old, current, future, malformed, and oversized payload fixtures.
4. Remove a migration only in a release whose policy permits dropping that stored format.

```kotlin
val codec = KotlinxJsonCacheCodec(
    serializer = Profile.serializer(),
    currentVersion = 2,
    migrations = mapOf(
        1 to JsonPayloadMigration { json, payload ->
            json.decodeFromString<ProfileV1>(payload).toProfile()
        },
    ),
)
```

A supported old payload is decoded and rewritten once in the current format. Unsupported or
malformed payloads are evicted and become misses. Migration exceptions are sanitized and never
include payload contents.

Use a new namespace when the meaning or identity of a resource changes, even if a codec could parse
the old JSON. A namespace is both a resource identity and a durable serialization contract.

## Room schema evolution

Room stores opaque payloads and cache metadata in one generic table. The checked-in schema is under
`cache-room/schemas`. When the physical schema changes, increment the database version, add explicit
Room migrations for every supported upgrade path, export the new schema, and test creation plus
reopen from the previous schema. Destructive migration is unsuitable when cached continuity is a
release requirement; if chosen for cache-only data, document that tradeoff in the changelog.
