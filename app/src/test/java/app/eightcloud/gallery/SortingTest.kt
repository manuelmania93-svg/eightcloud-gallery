package app.eightcloud.gallery

import app.eightcloud.gallery.data.MediaItem
import app.eightcloud.gallery.data.MediaSort
import app.eightcloud.gallery.data.SortField
import org.junit.Assert.assertEquals
import org.junit.Test

class SortingTest {

    private fun item(id: Long, name: String, date: Long, size: Long) = MediaItem(
        id = id,
        uri = "content://media/external/images/media/$id",
        isVideo = false,
        name = name,
        dateMillis = date,
        sizeBytes = size,
        width = 100,
        height = 100,
        durationMs = 0,
        bucket = "Camera",
        mimeType = "image/jpeg"
    )

    private val a = item(1, "b.jpg", 1000L, 500L)
    private val b = item(2, "a.jpg", 3000L, 100L)
    private val c = item(3, "c.jpg", 2000L, 900L)

    @Test
    fun dateDescending() {
        val sorted = listOf(a, b, c).sortedWith(MediaSort.comparator(SortField.DATE, false))
        assertEquals(listOf(b, c, a), sorted)
    }

    @Test
    fun dateAscending() {
        val sorted = listOf(a, b, c).sortedWith(MediaSort.comparator(SortField.DATE, true))
        assertEquals(listOf(a, c, b), sorted)
    }

    @Test
    fun sizeDescending() {
        val sorted = listOf(a, b, c).sortedWith(MediaSort.comparator(SortField.SIZE, false))
        assertEquals(listOf(c, a, b), sorted)
    }

    @Test
    fun sizeAscending() {
        val sorted = listOf(a, b, c).sortedWith(MediaSort.comparator(SortField.SIZE, true))
        assertEquals(listOf(b, a, c), sorted)
    }

    @Test
    fun nameAscendingIsCaseInsensitive() {
        val sorted = listOf(a, b, c).sortedWith(MediaSort.comparator(SortField.NAME, true))
        assertEquals(listOf(b, a, c), sorted)
    }

    @Test
    fun nameDescending() {
        val sorted = listOf(a, b, c).sortedWith(MediaSort.comparator(SortField.NAME, false))
        assertEquals(listOf(c, a, b), sorted)
    }
}
