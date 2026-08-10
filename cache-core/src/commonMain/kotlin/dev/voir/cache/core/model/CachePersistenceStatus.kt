package dev.voir.cache.core.model

/** Describes whether a manager-level persistent operation had an available backend. */
public enum class CachePersistenceStatus {
    /** The persistent backend completed the requested operation. */
    AVAILABLE,

    /** The manager is intentionally memory-only. */
    NOT_CONFIGURED,

    /** The configured backend failed and the manager preserved its memory/source path. */
    UNAVAILABLE,
}
