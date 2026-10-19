package app.eightcloud.gallery.ui

import app.eightcloud.gallery.data.MediaItem
import app.eightcloud.gallery.data.SortField
import org.junit.Assert.assertEquals
import org.junit.Test

class GridSectionCountTest {
    @Test
    fun everyPhotoInASectionCountsNotOnlyTheFirst() {
        val time = System.currentTimeMillis()
        val items = (1L..12L).map {
            MediaItem(it, "content://test/$it", false, "Photo$it.jpg", time, 1, 1, 1, 0, "Camera", "image/jpeg")
        }
        val entries = buildEntries(items, SortField.DATE)
        assertEquals(12, entries.filterIsInstance<GridEntry.Header>().single().count)
        assertEquals(12, entries.filterIsInstance<GridEntry.Media>().size)
    }
}
