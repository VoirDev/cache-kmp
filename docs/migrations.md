# Serialized-data changes

Cache keys and persistent values use Kotlinx serialization internally. Their encoded form is part
of the durable contract for a namespace. Accumulated pages are memory only and never participate in
durable compatibility.

## Compatible changes

Follow normal Kotlinx serialization compatibility rules. For example, adding a field with a default
value is generally readable from older JSON, while renaming or removing a required field is not.
Test manager recreation with data written by the previous application version whenever cached
continuity matters.

## Incompatible changes

Use a new namespace when any of these change incompatibly:

- the key type or its serialized identity;
- the value type or required JSON shape;
- the logical meaning of the cached data;
- memory versus persistent storage;
- stable payload or capacity limits.

```kotlin
val profiles = cacheScope<ProfileKey, Profile>("profiles-v2") {
    storage = CacheStorage.PERSISTENT
    expiresAfter = 30.minutes
}
```

A new namespace produces cache misses and repopulates from the loader. Old cache rows remain
disposable and are removed by internal retention. Use `clearAll()` during an application migration
when all old cached data should disappear immediately.

## Internal storage evolution

The public API intentionally does not expose Room migrations or custom payload codecs. Physical
schema evolution is owned by the library. Persisted values also carry an internal payload format
version; a payload written by a format the running library does not recognize is discarded rather
than decoded, so an unreadable entry becomes a cache miss instead of a decoding failure.

If a library release cannot preserve an internal cache schema, it rebuilds cache-only storage
rather than treating it as authoritative data, so an upgrade or a rollback empties the cache
instead of failing manager creation. Releases that do so say it in the changelog.
