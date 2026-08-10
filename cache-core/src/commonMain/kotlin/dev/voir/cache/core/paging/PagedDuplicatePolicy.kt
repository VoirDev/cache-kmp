package dev.voir.cache.core.paging

/** Deterministic handling for an item identity already present in accumulated state. */
public enum class PagedDuplicatePolicy {
    KEEP_EXISTING,
    REPLACE_EXISTING,
}
