package app.eightcloud.gallery.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DocumentsScanTest {
    @Test
    fun findsDocumentsAndSkipsHiddenAndAndroidDirs() {
        val root = kotlin.io.path.createTempDirectory("docs").toFile()
        try {
            File(root, "Download").mkdirs()
            File(root, "Download/a.PDF").writeText("x")
            File(root, "Download/pic.jpg").writeText("x")
            File(root, "notes.txt").writeText("hello")
            File(root, ".hidden").mkdirs()
            File(root, ".hidden/secret.pdf").writeText("x")
            File(root, "Android/data").mkdirs()
            File(root, "Android/data/app.pdf").writeText("x")
            val found = scanDocuments(root).map { it.name }.toSet()
            assertEquals(setOf("a.PDF", "notes.txt"), found)
            assertTrue(scanDocuments(root).first { it.name == "a.PDF" }.ext == "pdf")
        } finally {
            root.deleteRecursively()
        }
    }
}
