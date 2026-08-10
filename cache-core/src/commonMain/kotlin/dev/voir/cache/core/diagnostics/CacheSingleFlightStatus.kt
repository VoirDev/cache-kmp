package dev.voir.cache.core.diagnostics

/** Whether a caller started or joined manager-owned source work. */
public enum class CacheSingleFlightStatus {
    STARTED,
    JOINED,
}
