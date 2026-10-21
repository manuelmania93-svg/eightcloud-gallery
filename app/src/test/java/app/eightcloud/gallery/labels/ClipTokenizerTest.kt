package app.eightcloud.gallery.labels

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class ClipTokenizerTest {
    private val tok = ClipTokenizer(
        File("src/main/assets/clip_vocab.txt").readLines(),
        File("src/main/assets/clip_merges.txt").readLines()
    )

    private fun check(s: String, vararg ids: Long) = assertArrayEquals(s, ids, tok.encode(s))

    @Test fun golden() {
        check("dog on the beach", 49406, 1929, 525, 518, 2117, 49407)
        check("Pizza  with extra cheese!", 49406, 4474, 593, 4231, 4108, 256, 49407)
        check("M\u00fcnchen Stra\u00dfe 2023", 49406, 41235, 8983, 1894, 127, 253, 324, 273, 271, 273, 274, 49407)
        check("my cat's toys", 49406, 607, 2368, 568, 7162, 49407)
        check("sunset at the lake, 3 people", 49406, 3424, 536, 518, 2553, 267, 274, 1047, 49407)
        check("caf\u00e9 \u2615 time", 49406, 15304, 26561, 788, 49407)
        check("a photo of a car", 49406, 320, 1125, 539, 320, 1615, 49407)
    }
}
