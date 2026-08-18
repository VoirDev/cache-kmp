package dev.voir.cache.file

import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.CoroutineContext

internal actual fun defaultStorageCoroutineContext(): CoroutineContext = Dispatchers.IO
