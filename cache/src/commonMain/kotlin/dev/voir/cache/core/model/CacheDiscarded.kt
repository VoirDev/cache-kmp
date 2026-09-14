package dev.voir.cache.core.model

/**
 * Source-backed operation whose result lost a revision race and was not committed.
 *
 * The source value, when one was returned, is intentionally excluded so callers cannot mistake it
 * for authoritative cache state. [currentSnapshot] is the state committed by the winning command.
 * A losing typed source failure remains available through [sourceError] without being published to
 * observable cache state.
 *
 * @param V Non-null cached value type.
 * @param E Application-defined source error type.
 * @property currentSnapshot Authoritative snapshot after the rejected commit attempt.
 * @property sourceError Typed source failure that was discarded, or `null` for source success.
 */
internal data class CacheDiscarded<V : Any, E : Any>(
    val currentSnapshot: CacheSnapshot<V, E>,
    val sourceError: E? = null,
) : CacheResult<V, E>
