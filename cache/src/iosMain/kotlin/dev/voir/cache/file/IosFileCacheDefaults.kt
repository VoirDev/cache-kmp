package dev.voir.cache.file

import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.CoroutineContext

/**
 * Darwin storage dispatcher.
 *
 * Kotlin/Native does not expose an unbounded IO dispatcher, so blocking SQLite and POSIX calls
 * share the default dispatcher.
 */
internal actual fun defaultStorageCoroutineContext(): CoroutineContext = Dispatchers.Default
