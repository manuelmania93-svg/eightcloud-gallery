package app.eightcloud.gallery.vlm

import app.eightcloud.gallery.labels.Categories
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VlmParseTest {
    @Test
    fun parsesNormalAnswer() {
        val v = VlmParse.parse("MEME: yes\nDRAWN: no\nSCREENSHOT: no\nDESCRIPTION: cartoon bear with text caption")!!
        assertTrue(v.meme); assertFalse(v.drawn); assertFalse(v.screenshot)
        assertEquals("cartoon bear with text caption", v.description)
    }

    @Test
    fun stripsThinkBlock() {
        val v = VlmParse.parse("<think>hmm MEME: no</think>\nMEME: yes\nDRAWN: yes\nSCREENSHOT: no\nDESCRIPTION: anime girl")!!
        assertTrue(v.meme); assertTrue(v.drawn)
    }

    @Test
    fun refusalIsIgnored() {
        assertNull(VlmParse.parse("I'm sorry, I can't help with that."))
        assertNull(VlmParse.parse("I cannot describe this image."))
    }

    @Test
    fun missingFieldIsIgnored() {
        assertNull(VlmParse.parse("MEME: yes\nDESCRIPTION: a cat"))
        assertNull(VlmParse.parse("a photo of a cat"))
        assertNull(VlmParse.parse(""))
    }

    @Test
    fun refusalTextInsideDescriptionIsDropped() {
        val v = VlmParse.parse("MEME: no\nDRAWN: no\nSCREENSHOT: no\nDESCRIPTION: I'm sorry I can't")
        assertNotNull(v)
        assertEquals("", v!!.description)
    }

    private fun clip(meme: Float, drawn: Float) = "~meme:$meme,~drawn:$drawn"

    @Test
    fun unsureSelector() {
        assertTrue(VlmSelect.isUnsure(clip(0.2f, 0f), 20, 0f)) // meme grey zone with text
        assertFalse(VlmSelect.isUnsure(clip(0.2f, 0f), 3, 0f)) // no real text
        assertTrue(VlmSelect.isUnsure(clip(0f, 0.15f), 0, 0f)) // drawn grey zone
        assertFalse(VlmSelect.isUnsure(clip(0.9f, 0.9f), 50, 0f)) // CLIP is sure
        assertFalse(VlmSelect.isUnsure(clip(0.2f, 0.2f), 50, 0.9f)) // sensitive never goes to the VLM
        assertFalse(VlmSelect.isUnsure("", 50, 0f)) // no CLIP data
    }

    @Test
    fun vlmOverridesMemeAndDrawnButNeverSensitive() {
        val c = clip(0.05f, 0.05f)
        assertTrue(Categories.MEMES in Categories.forItem(0, 0f, c, 20, 0f, false, false, vlmMeme = true))
        assertFalse(Categories.MEMES in Categories.forItem(0, 0f, c, 3, 0f, false, false, vlmMeme = true)) // OCR still required
        assertTrue(Categories.DRAWN in Categories.forItem(0, 0f, c, 0, 0f, false, false, vlmDrawn = true))
        val strongClip = clip(0.9f, 0.9f)
        assertFalse(Categories.MEMES in Categories.forItem(0, 0f, strongClip, 20, 0f, false, false, vlmMeme = false))
        assertFalse(Categories.DRAWN in Categories.forItem(0, 0f, strongClip, 0, 0f, false, false, vlmDrawn = false))
        // Sensitive stays image-only in both directions.
        assertTrue(Categories.ADULT in Categories.forItem(0, 0f, c, 0, 0.8f, false, false, vlmMeme = false, vlmDrawn = false))
        assertFalse(Categories.ADULT in Categories.forItem(0, 0f, c, 0, 0.1f, false, false, vlmMeme = true, vlmDrawn = true))
        // No VLM row: unchanged CLIP behavior.
        assertTrue(Categories.DRAWN in Categories.forItem(0, 0f, clip(0f, 0.5f), 0, 0f, false, false))
    }

    @Test
    fun heatHasHysteresis() {
        assertFalse(VlmGate.hotNext(false, 44f, 0))
        assertTrue(VlmGate.hotNext(false, 45f, 0))
        assertTrue(VlmGate.hotNext(false, 30f, 3)) // severe thermal status
        assertTrue(VlmGate.hotNext(true, 43f, 0)) // still hot until 41
        assertFalse(VlmGate.hotNext(true, 41f, 0))
        assertTrue(VlmGate.hotNext(true, 35f, 3))
    }

    @Test
    fun lowBatteryOnlyWhenNotCharging() {
        assertTrue(VlmGate.lowBattery(false, 14))
        assertFalse(VlmGate.lowBattery(true, 5))
        assertFalse(VlmGate.lowBattery(false, 15))
    }
}
