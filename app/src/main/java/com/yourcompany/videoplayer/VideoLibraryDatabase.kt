package com.yourcompany.videoplayer

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
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "videos")
data class VideoEntity(
    @PrimaryKey val path: String,
    val id: Long,
    val title: String,
    val folderName: String,
    val size: Long,
    val duration: Long,
    val dateModified: Long,
    val mimeType: String,
    val networkMountId: String? = null
) {
    fun toVideoFile() = VideoFile(
        id = id,
        path = path,
        title = title,
        folderName = folderName,
        size = size,
        duration = duration,
        dateModified = dateModified,
        mimeType = mimeType,
        networkMountId = networkMountId
    )

    companion object {
        fun from(video: VideoFile) = VideoEntity(
            path = video.path,
            id = video.id,
            title = video.title,
            folderName = video.folderName,
            size = video.size,
            duration = video.duration,
            dateModified = video.dateModified,
            mimeType = video.mimeType,
            networkMountId = video.networkMountId
        )
    }
}

enum class VideoSortOrder(val preferenceValue: String, val label: String) {
    MODIFIED_DESC("modified_desc", "修改时间降序（最新优先）"),
    MODIFIED_ASC("modified_asc", "修改时间升序（最早优先）");

    companion object {
        fun fromPreference(value: String?): VideoSortOrder =
            entries.firstOrNull { it.preferenceValue == value } ?: MODIFIED_DESC
    }
}

@Dao
interface VideoDao {
    @Query("SELECT * FROM videos ORDER BY dateModified DESC, title COLLATE NOCASE ASC, path ASC")
    fun observeByModifiedDateDescending(): Flow<List<VideoEntity>>

    @Query("SELECT * FROM videos ORDER BY dateModified ASC, title COLLATE NOCASE ASC, path ASC")
    fun observeByModifiedDateAscending(): Flow<List<VideoEntity>>

    @Query("SELECT * FROM videos")
    suspend fun getAll(): List<VideoEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(videos: List<VideoEntity>)

    @Query("DELETE FROM videos WHERE path IN (:paths)")
    suspend fun deleteByPaths(paths: List<String>)
}

@Database(entities = [VideoEntity::class], version = 2, exportSchema = true)
abstract class VideoLibraryDatabase : RoomDatabase() {
    abstract fun videoDao(): VideoDao

    companion object {
        @Volatile
        private var instance: VideoLibraryDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE videos ADD COLUMN networkMountId TEXT")
            }
        }

        fun getInstance(context: Context): VideoLibraryDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    VideoLibraryDatabase::class.java,
                    "video-library.db"
                ).addMigrations(MIGRATION_1_2).build().also { instance = it }
            }
    }
}
