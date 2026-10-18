package app.eightcloud.gallery.backup

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * One row per known media file. The content hash is the identity: a renamed
 * or moved file keeps its hash and is never uploaded twice.
 */
@Entity(tableName = "backup_index")
data class BackupEntry(
    @PrimaryKey val contentHash: String,
    val mediaStoreId: Long,
    val name: String,
    val sizeBytes: Long,
    val isVideo: Boolean,
    val status: String, // PENDING, UPLOADING, BACKED_UP, CLOUD_ONLY, MISSING_IN_CLOUD
    val telegramMessageIds: String, // comma-separated chunk message ids in Saved Messages
    val chunkIvs: String, // comma-separated hex ivs, one per chunk
    val uploadedAt: Long,
    val lastSeenAt: Long,
    val errorMessage: String = ""
) {
    companion object {
        const val PENDING = "PENDING"
        const val UPLOADING = "UPLOADING"
        const val BACKED_UP = "BACKED_UP"
        const val CLOUD_ONLY = "CLOUD_ONLY"
        const val MISSING_IN_CLOUD = "MISSING_IN_CLOUD"
        const val FAILED = "FAILED"
    }
}

@Dao
interface BackupDao {
    @Query("SELECT * FROM backup_index")
    suspend fun all(): List<BackupEntry>

    @Query("SELECT * FROM backup_index WHERE status = :status")
    suspend fun byStatus(status: String): List<BackupEntry>

    @Query("SELECT * FROM backup_index WHERE contentHash = :hash")
    suspend fun byHash(hash: String): BackupEntry?

    @Query("SELECT * FROM backup_index WHERE mediaStoreId = :mediaStoreId")
    suspend fun byMediaStoreId(mediaStoreId: Long): BackupEntry?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: BackupEntry)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entries: List<BackupEntry>)

    @Query("UPDATE backup_index SET status = :status, telegramMessageIds = :messageIds, chunkIvs = :ivs, uploadedAt = :uploadedAt WHERE contentHash = :hash")
    suspend fun markUploaded(hash: String, status: String, messageIds: String, ivs: String, uploadedAt: Long)

    @Query("UPDATE backup_index SET status = :status WHERE contentHash = :hash")
    suspend fun setStatus(hash: String, status: String)

    @Query("SELECT COUNT(*) FROM backup_index WHERE status = 'BACKED_UP' OR status = 'CLOUD_ONLY'")
    suspend fun backedUpCount(): Int

    @Query("UPDATE backup_index SET status = 'FAILED', errorMessage = :message WHERE contentHash = :hash")
    suspend fun markFailed(hash: String, message: String)

    @Query("UPDATE backup_index SET status = 'PENDING', errorMessage = '' WHERE status = 'FAILED'")
    suspend fun resetFailed(): Int

    @Query("SELECT * FROM backup_index WHERE status = 'PENDING' AND mediaStoreId IN (:ids)")
    suspend fun pendingByIds(ids: List<Long>): List<BackupEntry>
}

@Database(entities = [BackupEntry::class], version = 2, exportSchema = false)
abstract class BackupDb : RoomDatabase() {
    abstract fun dao(): BackupDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE backup_index ADD COLUMN errorMessage TEXT NOT NULL DEFAULT ''")
            }
        }

        @Volatile private var instance: BackupDb? = null

        fun get(context: Context): BackupDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                BackupDb::class.java,
                "backup-index.db"
            ).addMigrations(MIGRATION_1_2).build().also { instance = it }
        }
    }
}
