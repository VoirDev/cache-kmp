package dev.voir.cache.core.diagnostics

/** Receives optional non-sensitive cache diagnostics. */
public fun interface CacheEventListener {
    /**
     * Handles one [event]. Cache implementations isolate operations from listener failures.
     *
     * @param event Non-sensitive diagnostic event.
     */
    public fun onEvent(event: CacheEvent)
}

