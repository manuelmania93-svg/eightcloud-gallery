package app.eightcloud.gallery

import app.eightcloud.gallery.data.MediaSort
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class ColumnsAndLabelsTest {

    @Test
    fun clampColumnsKeepsRange() {
        assertEquals(2, MediaSort.clampColumns(1))
        assertEquals(2, MediaSort.clampColumns(2))
        assertEquals(4, MediaSort.clampColumns(4))
        assertEquals(6, MediaSort.clampColumns(6))
        assertEquals(6, MediaSort.clampColumns(99))
    }

    @Test
    fun dateLabels() {
        val today = LocalDate.of(2026, 10, 18)
        assertEquals("Today", MediaSort.dateLabel(today, today))
        assertEquals("Yesterday", MediaSort.dateLabel(today.minusDays(1), today))
        assertEquals("Fri, 16 Oct 2026", MediaSort.dateLabel(today.minusDays(2), today))
    }
}
