package app.eightcloud.gallery.vlm

/** What the second pass concluded about one photo. Pure data, stored in vlm_results. */
data class VlmVerdict(val meme: Boolean, val drawn: Boolean, val screenshot: Boolean, val description: String)

/**
 * Parses the model answer. Returns null for refusals and malformed output, so they never
 * change a category or a tag. Pure function, unit-tested.
 */
object VlmParse {
    private val refusal = Regex(
        """i (can('|\u2019)?t|cannot|am unable to|won('|\u2019)?t|'m not able to|am not able to)\b|i('|\u2019)?m sorry|unable to (help|assist|describe)|as an ai|against my (guidelines|policy)|not (able|allowed) to (describe|help|assist)""",
        RegexOption.IGNORE_CASE
    )
    private val think = Regex("""<think>.*?</think>""", RegexOption.DOT_MATCHES_ALL)
    private fun yn(t: String, key: String): Boolean? =
        Regex("""$key:\s*(yes|no)\b""", RegexOption.IGNORE_CASE).find(t)?.groupValues?.get(1)?.lowercase()?.let { it == "yes" }

    fun parse(raw: String): VlmVerdict? {
        val t = think.replace(raw, "").trim()
        val meme = yn(t, "MEME")
        val drawn = yn(t, "DRAWN")
        val shot = yn(t, "SCREENSHOT")
        if (meme == null || drawn == null || shot == null) return null // refusal or format fail
        if (refusal.containsMatchIn(t) && !t.contains("DESCRIPTION:", ignoreCase = true)) return null
        val desc = Regex("""DESCRIPTION:\s*(.+)""", RegexOption.IGNORE_CASE).find(t)?.groupValues?.get(1)
            ?.lineSequence()?.first()?.trim()?.take(200).orEmpty()
        return VlmVerdict(meme, drawn, shot, if (refusal.containsMatchIn(desc)) "" else desc)
    }
}
