package app.eightcloud.gallery.labels

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CategoriesTest {

    @Test
    fun facesMakePeople() {
        val cats = Categories.forItem(2, emptySet(), -1f, false, true)
        assertTrue(Categories.PEOPLE in cats)
    }

    @Test
    fun noFacesNoPeople() {
        val cats = Categories.forItem(0, setOf("Sky", "Tree"), -1f, false, true)
        assertTrue(Categories.PEOPLE !in cats)
        assertTrue(Categories.OUTDOOR in cats)
    }

    @Test
    fun nsfwAboveThreshold() {
        assertTrue(Categories.ADULT in Categories.forItem(0, emptySet(), 0.9f, false, false))
        assertTrue(Categories.ADULT !in Categories.forItem(0, emptySet(), 0.3f, false, false))
    }

    @Test
    fun screenshotWithTextIsMeme() {
        val cats = Categories.forItem(0, setOf("Text"), -1f, true, false)
        assertTrue(Categories.MEMES in cats)
    }

    @Test
    fun photoOfDocumentIsDocument() {
        val cats = Categories.forItem(0, setOf("Text", "Document", "Paper"), -1f, false, true)
        assertTrue(Categories.DOCUMENTS in cats)
    }

    @Test
    fun sceneLabelsMap() {
        assertTrue(Categories.FOOD in Categories.forItem(0, setOf("Food"), -1f, false, false))
        assertTrue(Categories.ANIMALS in Categories.forItem(0, setOf("Dog"), -1f, false, false))
        assertTrue(Categories.CARS in Categories.forItem(0, setOf("Car"), -1f, false, false))
        assertTrue(Categories.BEACH in Categories.forItem(0, setOf("Beach"), -1f, false, false))
        assertTrue(Categories.NIGHT in Categories.forItem(0, setOf("Night"), -1f, false, false))
    }

    @Test
    fun everyCategoryHasTitle() {
        Categories.ALL.forEach { assertTrue(Categories.title(it).isNotBlank()) }
        assertEquals(10, Categories.ALL.size)
    }
}
