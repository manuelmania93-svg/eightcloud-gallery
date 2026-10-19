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
    val errorMessage: String = "",
    val partChecksums: String = "", // comma-separated SHA-256 hex, one per part
    val uploadedPartIds: String = "", // comma-separated telegram message ids, positional per part; blank = not yet uploaded
    val keyTag: String = "ks" // which key encrypts this entry: 'ks' device keystore, 'pp' passphrase DEK
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

    @Query("SELECT * FROM backup_index WHERE contentHash LIKE :prefix || '%' LIMIT 1")
    suspend fun byHashPrefix(prefix: String): BackupEntry?

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

    @Query("SELECT COUNT(*) FROM backup_index")
    suspend fun rowCount(): Int

    @Query("UPDATE backup_index SET status = 'FAILED', errorMessage = :message WHERE contentHash = :hash")
    suspend fun markFailed(hash: String, message: String)

    @Query("UPDATE backup_index SET status = :status, telegramMessageIds = :messageIds, chunkIvs = :ivs, partChecksums = :checksums, uploadedAt = :uploadedAt, keyTag = :keyTag WHERE contentHash = :hash")
    suspend fun markUploadedWithChecksums(hash: String, status: String, messageIds: String, ivs: String, checksums: String, uploadedAt: Long, keyTag: String)

    @Query("UPDATE backup_index SET status = 'PENDING', errorMessage = '' WHERE status = 'FAILED'")
    suspend fun resetFailed(): Int

    @Query("UPDATE backup_index SET chunkIvs = :ivs, partChecksums = :checksums WHERE contentHash = :hash")
    suspend fun setUploadStart(hash: String, ivs: String, checksums: String)

    @Query("UPDATE backup_index SET uploadedPartIds = :partIds WHERE contentHash = :hash")
    suspend fun setUploadedPartIds(hash: String, partIds: String)

    @Query("SELECT * FROM backup_index WHERE status = 'PENDING' AND mediaStoreId IN (:ids)")
    suspend fun pendingByIds(ids: List<Long>): List<BackupEntry>
}

/** Items hidden behind the app lock (secure folder). */
@Entity(tableName = "secure_entries")
data class SecureEntry(
    @PrimaryKey val mediaStoreId: Long,
    val name: String,
    val isVideo: Boolean,
    val addedAt: Long
)

@Dao
interface SecureDao {
    @Query("SELECT mediaStoreId FROM secure_entries")
    suspend fun ids(): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entries: List<SecureEntry>)

    @Query("DELETE FROM secure_entries WHERE mediaStoreId IN (:ids)")
    suspend fun delete(ids: List<Long>)

    @Query("SELECT COUNT(*) FROM secure_entries")
    suspend fun count(): Int
}

/** On-device analysis results per media item (v0.7.0 categorization). */
@Entity(tableName = "media_labels")
data class MediaLabelEntry(
    @PrimaryKey val mediaStoreId: Long,
    val faceCount: Int,
    val labels: String, // comma-separated ML Kit labels
    val nsfwScore: Float, // -1 when not computed
    val scannedAt: Long,
    val faceMaxRatio: Float = 0f, // largest face bounding-box area / image area
    val ocrChars: Int = 0, // characters found by on-device OCR; -1 = not run
    val labelConfs: String = "", // comma-separated label:confidence pairs
    val scanVersion: Int = 1 // < CURRENT_SCAN_VERSION rows get rescanned lazily
)

/** Manual per-item category corrections from the user; override the scanner. */
@Entity(tableName = "category_overrides", primaryKeys = ["mediaStoreId", "category"])
data class CategoryOverride(
    val mediaStoreId: Long,
    val category: String,
    val excluded: Boolean, // true = "not this category", false = forced in
    val setAt: Long
)

@Dao
interface CategoryOverrideDao {
    @Query("SELECT * FROM category_overrides")
    suspend fun all(): List<CategoryOverride>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(override: CategoryOverride)

    @Query("DELETE FROM category_overrides WHERE mediaStoreId = :id AND category = :category")
    suspend fun delete(id: Long, category: String)

    @Query("SELECT * FROM category_overrides WHERE mediaStoreId = :id")
    suspend fun forItem(id: Long): List<CategoryOverride>
}

@Dao
interface MediaLabelDao {
    @Query("SELECT * FROM media_labels")
    suspend fun all(): List<MediaLabelEntry>

    @Query("SELECT * FROM media_labels WHERE mediaStoreId = :id")
    suspend fun byId(id: Long): MediaLabelEntry?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: MediaLabelEntry)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entries: List<MediaLabelEntry>)

    @Query("SELECT COUNT(*) FROM media_labels")
    suspend fun count(): Int

    @Query("DELETE FROM media_labels WHERE mediaStoreId = :id")
    suspend fun delete(id: Long)

    @Query("SELECT mediaStoreId FROM media_labels WHERE scanVersion < :version")
    suspend fun outdatedIds(version: Int): List<Long>
}

/** EXIF GPS cache per media item (map view). lat/lon NULL = scanned, no GPS. */
@Entity(tableName = "geo_entries")
data class GeoEntry(
    @PrimaryKey val mediaStoreId: Long,
    val latitude: Double?,
    val longitude: Double?,
    val scannedAt: Long
)

@Dao
interface GeoDao {
    @Query("SELECT * FROM geo_entries WHERE latitude IS NOT NULL")
    suspend fun withLocation(): List<GeoEntry>

    @Query("SELECT mediaStoreId FROM geo_entries")
    suspend fun scannedIds(): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entries: List<GeoEntry>)

    @Query("SELECT COUNT(*) FROM geo_entries WHERE latitude IS NOT NULL")
    suspend fun locatedCount(): Int
}

@Database(entities = [BackupEntry::class, SecureEntry::class, MediaLabelEntry::class, GeoEntry::class, CategoryOverride::class], version = 9, exportSchema = false)
abstract class BackupDb : RoomDatabase() {
    abstract fun dao(): BackupDao
    abstract fun secureDao(): SecureDao
    abstract fun mediaLabelDao(): MediaLabelDao
    abstract fun geoDao(): GeoDao
    abstract fun categoryOverrideDao(): CategoryOverrideDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE backup_index ADD COLUMN errorMessage TEXT NOT NULL DEFAULT ''")
            }
        }
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE backup_index ADD COLUMN partChecksums TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS geo_entries (" +
                        "mediaStoreId INTEGER NOT NULL PRIMARY KEY, " +
                        "latitude REAL, " +
                        "longitude REAL, " +
                        "scannedAt INTEGER NOT NULL)"
                )
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS media_labels (" +
                        "mediaStoreId INTEGER NOT NULL PRIMARY KEY, " +
                        "faceCount INTEGER NOT NULL, " +
                        "labels TEXT NOT NULL, " +
                        "nsfwScore REAL NOT NULL, " +
                        "scannedAt INTEGER NOT NULL)"
                )
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE media_labels ADD COLUMN faceMaxRatio REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE media_labels ADD COLUMN ocrChars INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE media_labels ADD COLUMN labelConfs TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE media_labels ADD COLUMN scanVersion INTEGER NOT NULL DEFAULT 1")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS category_overrides (" +
                        "mediaStoreId INTEGER NOT NULL, " +
                        "category TEXT NOT NULL, " +
                        "excluded INTEGER NOT NULL, " +
                        "setAt INTEGER NOT NULL, " +
                        "PRIMARY KEY(mediaStoreId, category))"
                )
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE backup_index ADD COLUMN keyTag TEXT NOT NULL DEFAULT 'ks'")
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE backup_index ADD COLUMN uploadedPartIds TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS secure_entries (" +
                        "mediaStoreId INTEGER NOT NULL PRIMARY KEY, " +
                        "name TEXT NOT NULL, " +
                        "isVideo INTEGER NOT NULL, " +
                        "addedAt INTEGER NOT NULL)"
                )
            }
        }

        @Volatile private var instance: BackupDb? = null

        fun get(context: Context): BackupDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                BackupDb::class.java,
                "backup-index.db"
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9).build().also { instance = it }
        }
    }
}
