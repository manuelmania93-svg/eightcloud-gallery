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
    private val monthFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM yyyy")

    fun monthLabel(day: LocalDate): String = day.format(monthFormatter)

    fun sizeLabel(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "%.1f KB".format(kb)
        val mb = kb / 1024.0
        if (mb < 1024) return "%.1f MB".format(mb)
        return "%.1f GB".format(mb / 1024.0)
    }

    fun letterLabel(name: String): String {
        val first = name.firstOrNull { it.isLetterOrDigit() } ?: return "#"
        return if (first.isLetter()) first.uppercaseChar().toString() else "#"
    }

    fun dateLabel(day: LocalDate, today: LocalDate): String = when (day) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> day.format(formatter)
    }
}
