package app.eightcloud.gallery.vlm

import app.eightcloud.gallery.labels.Categories

/**
 * Picks the photos CLIP is unsure about: meme or drawn share in the grey zone just below/around
 * the category thresholds. Thresholds are hypotheses until counted on real data (the Settings
 * screen shows the real count).
 */
object VlmSelect {
    const val MEME_LOW = 0.12f
    const val MEME_HIGH = 0.60f
    const val DRAWN_LOW = 0.10f
    const val DRAWN_HIGH = 0.50f
    const val MIN_OCR = 8

    fun isUnsure(labelConfs: String, ocrChars: Int, nsfwScore: Float): Boolean {
        if (nsfwScore >= Categories.NSFW_THRESHOLD) return false // Sensitive stays image-only, never sent to the VLM
        val clip = Categories.parseConfs(labelConfs).filter { it.first.startsWith("~") }
            .associate { it.first.substring(1) to it.second }
        if (clip.isEmpty()) return false
        val meme = clip["meme"] ?: 0f
        val drawn = clip["drawn"] ?: 0f
        return (ocrChars >= MIN_OCR && meme in MEME_LOW..MEME_HIGH) || drawn in DRAWN_LOW..DRAWN_HIGH
    }

    /** Unsure photos not yet processed, excluding secure-folder items. */
    suspend fun pending(context: android.content.Context): List<Long> {
        val db = app.eightcloud.gallery.backup.BackupDb.get(context)
        val done = db.vlmDao().ids().toSet()
        val secure = db.secureDao().ids().toSet()
        return db.mediaLabelDao().all().filter {
            it.mediaStoreId !in done && it.mediaStoreId !in secure && isUnsure(it.labelConfs, it.ocrChars, it.nsfwScore)
        }.map { it.mediaStoreId }
    }
}
