package dev.voir.cache.core.file

import dev.voir.cache.core.model.FetchResult

/** Application-owned source that streams content and returns typed metadata or failure. */
internal fun interface FileFetcher<K : Any, E : Any> {
    suspend fun fetch(key: K, sink: FileWriteSink): FetchResult<FileContentMetadata, E>
}
