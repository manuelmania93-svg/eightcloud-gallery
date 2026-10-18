package app.eightcloud.gallery.data

import java.time.LocalDate
import java.time.format.DateTimeFormatter

enum class SortField { DATE, SIZE, NAME }

object MediaSort {

    const val MIN_COLUMNS = 2
    const val MAX_COLUMNS = 6

    fun clampColumns(columns: Int): Int = columns.coerceIn(MIN_COLUMNS, MAX_COLUMNS)

    fun comparator(field: SortField, ascending: Boolean): Comparator<MediaItem> {
        val base: Comparator<MediaItem> = when (field) {
            SortField.DATE -> compareBy { it.dateMillis }
            SortField.SIZE -> compareBy { it.sizeBytes }
            SortField.NAME -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }
        }
        val byId = base.thenBy { it.id }
        return if (ascending) byId else byId.reversed()
    }

    private val formatter: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, d MMM yyyy")

    fun dateLabel(day: LocalDate, today: LocalDate): String = when (day) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> day.format(formatter)
    }
}
