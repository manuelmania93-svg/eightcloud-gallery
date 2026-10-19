package app.eightcloud.gallery.labels

import org.junit.Assert.assertTrue
import org.junit.Test

class CategoriesTest {

    private fun confs(vararg pairs: Pair<String, Float>): String =
        pairs.joinToString(",") { it.first + ":" + it.second }

    @Test
    fun bigFaceMakesPeople() {
        val cats = Categories.forItem(1, 0.05f, confs(), 0, -1f, false, true)
        assertTrue(Categories.PEOPLE in cats)
    }

    @Test
    fun tinyBackgroundFaceDoesNotMakePeople() {
        // One face covering 0.1% of the image: background bystander.
        val cats = Categories.forItem(1, 0.001f, confs(), 0, -1f, false, true)
        assertTrue(Categories.PEOPLE !in cats)
    }

    @Test
    fun groupOfMediumFacesMakesPeople() {
        val cats = Categories.forItem(3, 0.005f, confs(), 0, -1f, false, true)
        assertTrue(Categories.PEOPLE in cats)
    }

    @Test
    fun legacyRowKeepsOldPeopleRule() {
        // scanVersion 1 row: no ratio recorded (0), faces present.
        val cats = Categories.forItem(2, 0f, confs(), 0, -1f, false, true)
        assertTrue(Categories.PEOPLE in cats)
    }

    @Test
    fun nsfwAboveThreshold() {
        assertTrue(Categories.ADULT in Categories.forItem(0, 0f, confs(), 0, 0.9f, false, false))
        assertTrue(Categories.ADULT !in Categories.forItem(0, 0f, confs(), 0, 0.3f, false, false))
    }

    @Test
    fun strongOutdoorLabelWins() {
        val cats = Categories.forItem(0, 0f, confs("Sky" to 0.9f), 0, -1f, false, true)
        assertTrue(Categories.OUTDOOR in cats)
    }

    @Test
    fun twoMediumOutdoorLabelsWin() {
        val cats = Categories.forItem(0, 0f, confs("Tree" to 0.62f, "Grass" to 0.61f), 0, -1f, false, true)
        assertTrue(Categories.OUTDOOR in cats)
    }

    @Test
    fun oneWeakLabelLoses() {
        val cats = Categories.forItem(0, 0f, confs("Sky" to 0.55f), 0, -1f, false, true)
        assertTrue(Categories.OUTDOOR !in cats)
    }

    @Test
    fun screenshotsNeverGetSceneCategories() {
        val cats = Categories.forItem(0, 0f, confs("Sky" to 0.95f, "Tree" to 0.9f), 0, -1f, true, false)
        assertTrue(Categories.OUTDOOR !in cats)
    }

    @Test
    fun cameraPhotoWithLotsOfTextIsDocument() {
        val cats = Categories.forItem(0, 0f, confs(), 400, -1f, false, true)
        assertTrue(Categories.DOCUMENTS in cats)
    }

    @Test
    fun cameraPhotoWithoutOcrTextIsNotDocument() {
        val cats = Categories.forItem(0, 0f, confs("Text" to 0.7f, "Document" to 0.65f), 0, -1f, false, true)
        assertTrue(Categories.DOCUMENTS !in cats)
    }

    @Test
    fun screenshotWithTextIsMeme() {
        val cats = Categories.forItem(0, 0f, confs(), 120, -1f, true, false)
        assertTrue(Categories.MEMES in cats)
    }

    @Test
    fun screenshotWithoutTextIsNotMeme() {
        val cats = Categories.forItem(0, 0f, confs(), 4, -1f, true, false)
        assertTrue(Categories.MEMES !in cats)
    }

    @Test
    fun parseConfsReadsPairs() {
        val parsed = Categories.parseConfs("Sky:0.91,Tree:0.60")
        assertTrue(parsed.size == 2)
        assertTrue(parsed[0].first == "Sky" && parsed[0].second == 0.91f)
    }
}
