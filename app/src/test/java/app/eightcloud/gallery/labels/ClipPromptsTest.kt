package app.eightcloud.gallery.labels

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipPromptsTest {
    private fun unit(vararg v: Float) = ClipPrompts.normalize(floatArrayOf(*v))

    private val prompts = ClipPrompts(
        cats = listOf("food", "food", "cars", "other"),
        names = listOf("a pizza", "a meal", "a car", "a room"),
        matrix = arrayOf(unit(1f, 0f, 0f), unit(0.9f, 0.1f, 0f), unit(0f, 1f, 0f), unit(0f, 0f, 1f))
    )

    @Test
    fun picksTheClosestCategoryAndSharesSumToOne() {
        val s = prompts.scores(unit(1f, 0.05f, 0f))
        assertTrue(s.getValue("food") > 0.9f)
        assertEquals(1f, s.values.sum(), 0.001f)
    }

    @Test
    fun rejectedLookalikeLosesTheCategory() {
        val emb = unit(1f, 0.05f, 0f)
        val rejected = unit(1f, 0.04f, 0f)
        val plain = prompts.scores(emb).getValue("food")
        val learned = prompts.scores(emb, mapOf("food" to listOf(rejected))).getValue("food")
        assertTrue(learned < plain * 0.3f)
    }

    @Test
    fun topReturnsBestPromptFirst() {
        assertEquals("a car", prompts.top(unit(0f, 1f, 0f), 3).first().first)
    }

    @Test
    fun mergeReplacesOldClipLabelsAndKeepsMlKit() {
        val first = ClipPrompts.mergeIntoLabelConfs("Sky:0.90", mapOf("food" to 0.5f))
        val second = ClipPrompts.mergeIntoLabelConfs(first, mapOf("food" to 0.2f))
        assertEquals("Sky:0.90,~food:0.20", second)
    }

    @Test
    fun blobRoundTrips() {
        val v = unit(0.3f, 0.4f, 0.5f)
        assertEquals(v.toList(), ClipPrompts.fromBlob(ClipPrompts.toBlob(v)).toList())
    }

    @Test
    fun clipSharesDriveCategories() {
        val cats = Categories.forItem(0, 0f, "Sky:0.30,~food:0.62,~other:0.30", 0, -1f, false, true)
        assertTrue(Categories.FOOD in cats)
        val none = Categories.forItem(0, 0f, "~food:0.20,~other:0.80", 0, -1f, false, true)
        assertTrue(Categories.FOOD !in none)
        val shot = Categories.forItem(0, 0f, "~food:0.9", 0, -1f, true, false)
        assertTrue(Categories.FOOD !in shot)
    }
}
