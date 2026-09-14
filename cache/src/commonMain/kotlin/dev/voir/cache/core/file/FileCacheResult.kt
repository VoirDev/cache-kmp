package dev.voir.cache.core.file

import dev.voir.cache.core.model.CacheFreshness
import dev.voir.cache.core.model.CacheSnapshot

/** Result of a file operation that may invoke a source or commit streamed content. */
internal sealed interface FileCacheResult<out E : Any>

/** Successful operation containing one authoritative committed file descriptor. */
internal data class FileCacheSuccess(
    val file: CachedFile,
    val freshness: CacheFreshness,
) : FileCacheResult<Nothing> {
    init {
        require(freshness != CacheFreshness.ABSENT) {
            "A successful file-cache result must be FRESH or STALE."
        }
    }
}

/** Typed application source failure with an optional usable stale file. */
internal data class FileCacheFailure<E : Any>(
    val error: E,
    val staleFile: CachedFile? = null,
) : FileCacheResult<E>

/** Hard policy reason that prevented staged content from becoming committed state. */
internal enum class FileCacheRejectionReason {
    ENTRY_TOO_LARGE,
    CAPACITY_UNAVAILABLE,
}

/** Policy rejection with an optional previously committed stale file. */
internal data class FileCacheRejected(
    val reason: FileCacheRejectionReason,
    val staleFile: CachedFile? = null,
) : FileCacheResult<Nothing>

/** File-store failure with an optional previously committed stale file. */
internal data class FileCacheUnavailable(
    val staleFile: CachedFile? = null,
) : FileCacheResult<Nothing>

/** Source completion rejected because a newer mutation won its revision race. */
internal data class FileCacheDiscarded<E : Any>(
    val currentSnapshot: CacheSnapshot<CachedFile, E>,
    val sourceError: E? = null,
) : FileCacheResult<E>
