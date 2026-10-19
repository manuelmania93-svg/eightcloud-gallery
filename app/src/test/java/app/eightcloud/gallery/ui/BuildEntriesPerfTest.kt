package app.eightcloud.gallery.ui

import app.eightcloud.gallery.data.MediaItem
import app.eightcloud.gallery.data.SortField
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildEntriesPerfTest {

    private fun syntheticItems(count: Int): List<MediaItem> {
        val base = 1_600_000_000_000L
        return (1..count).map {
            MediaItem(
                id = it.toLong(),
                uri = "content://synthetic/$it",
                isVideo = it % 9 == 0,
                name = "SYN_$it.jpg",
                dateMillis = base + it * 3_600_000L,
                sizeBytes = 1_000_000L + it,
                width = 4000,
                height = 3000,
                durationMs = 0L,
                bucket = "Synthetic",
                mimeType = "image/jpeg"
            )
        }
    }

    @Test
    fun buildEntriesHandles41kItems() {
        val items = syntheticItems(41_052)
        val start = System.nanoTime()
        val entries = buildEntries(items, SortField.DATE)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertTrue("headers should be added", entries.size > items.size)
        assertTrue("buildEntries took ${elapsedMs}ms for 41k items", elapsedMs < 5_000)
    }

    @Test
    fun buildEntries41kGroupsByDay() {
        val items = syntheticItems(41_052)
        val entries = buildEntries(items, SortField.DATE)
        val mediaCount = entries.count { it !is GridEntry.Header }
        assertTrue(mediaCount == items.size)
    }
}
