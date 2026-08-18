package dev.voir.cache.sample.file

import dev.voir.cache.CacheFileOptions
import dev.voir.cache.CacheManager
import dev.voir.cache.cacheFile
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/** Demonstrates streamed content surviving manager and process-cache recreation. */
class FileConsumerTest {
    @Test
    fun `restart leases the file without another source download`() = runTest {
        val root = createTempDirectory("cache-kmp-file-consumer-")
        var sourceCalls = 0
        val options = CacheFileOptions(
            namespace = "sample.documents",
            maxEntryBytes = 1_000,
            maxEntries = 10,
            maxBytes = 5_000,
            partition = "account-1",
            mediaType = "application/pdf",
        )
        try {
            val firstManager = CacheManager(rootDirectory = root, scope = this)
            val firstFile = firstManager.cacheFile("report", options) { sink ->
                sourceCalls += 1
                sink.write(byteArrayOf(1, 2))
                sink.write(byteArrayOf(3, 4))
            }
            try {
                assertContentEquals(
                    byteArrayOf(1, 2, 3, 4),
                    Files.readAllBytes(Path.of(firstFile.path)),
                )
            } finally {
                firstFile.close()
            }
            firstManager.close()

            val restartedManager = CacheManager(rootDirectory = root, scope = this)
            val restoredFile = restartedManager.cacheFile("report", options) {
                error("Fresh durable content should skip the source")
            }
            restoredFile.close()
            assertEquals(1, sourceCalls)
            restartedManager.close()
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
