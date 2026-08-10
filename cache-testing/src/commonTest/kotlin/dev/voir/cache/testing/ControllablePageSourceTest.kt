package dev.voir.cache.testing

import dev.voir.cache.core.model.FetchSuccess
import dev.voir.cache.core.paging.FetchedPage
import dev.voir.cache.core.paging.PageLoadRequest
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** Verifies deterministic page scripting and race gates without wall-clock delays. */
class ControllablePageSourceTest {
    @Test
    fun `next request can be inspected and released deterministically`() = runTest {
        val source = ControllablePageSource<String, Int, String, String>()
        source.enqueue(FetchSuccess(FetchedPage(listOf("item"), nextCursor = 2)))
        source.pauseNextRequest()
        val result = async { source.fetch(PageLoadRequest("query", cursor = 1)) }

        assertEquals(PageLoadRequest("query", 1), source.awaitNextRequest())
        assertFalse(result.isCompleted)
        source.releaseNextRequest()

        assertEquals(listOf("item"), result.await().let { it as FetchSuccess }.value.items)
        assertEquals(listOf(PageLoadRequest("query", 1)), source.requests())
    }
}
