package app.eightcloud.gallery

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.eightcloud.gallery.backup.BackupDb
import app.eightcloud.gallery.backup.MediaLabelEntry
import app.eightcloud.gallery.backup.VlmResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real upgrade: a database at version 12 (no vlm_results) opens at 13, keeps old rows, and stores VLM rows. */
@RunWith(AndroidJUnit4::class)
class VlmMigrationTest {
    @Test
    fun upgradeFrom12KeepsDataAndCreatesVlmTable() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "mig-test.db"
        ctx.deleteDatabase(name)
        val db1 = Room.databaseBuilder(ctx, BackupDb::class.java, name).build()
        db1.mediaLabelDao().upsert(MediaLabelEntry(42L, 1, "Dog", 0.1f, 1L, labelConfs = "~meme:0.2"))
        db1.openHelper.writableDatabase.execSQL("DROP TABLE vlm_results")
        db1.openHelper.writableDatabase.execSQL("PRAGMA user_version = 12")
        db1.close()

        val db2 = Room.databaseBuilder(ctx, BackupDb::class.java, name).addMigrations(BackupDb.MIGRATION_12_13).build()
        val old = db2.mediaLabelDao().byId(42L)
        assertNotNull(old)
        assertEquals("~meme:0.2", old!!.labelConfs)
        db2.vlmDao().upsert(VlmResult(42L, true, false, false, "cartoon bear", 1, 5L))
        val rows = db2.vlmDao().all()
        assertEquals(1, rows.size)
        assertTrue(rows[0].meme)
        assertEquals("cartoon bear", rows[0].description)
        db2.close()
        ctx.deleteDatabase(name)
    }
}
