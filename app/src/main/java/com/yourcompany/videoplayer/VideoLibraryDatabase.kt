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
    val mimeType: String
) {
    fun toVideoFile() = VideoFile(
        id = id,
        path = path,
        title = title,
        folderName = folderName,
        size = size,
        duration = duration,
        dateModified = dateModified,
        mimeType = mimeType
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
            mimeType = video.mimeType
        )
    }
}

enum class VideoSortOrder(val preferenceValue: String, val label: String) {
    MODIFIED_DESC("modified_desc", "修改时间（最新优先）"),
    NAME_ASC("name_asc", "名称升序（A-Z）"),
    NAME_DESC("name_desc", "名称降序（Z-A）");

    companion object {
        fun fromPreference(value: String?): VideoSortOrder =
            entries.firstOrNull { it.preferenceValue == value } ?: MODIFIED_DESC
    }
}

@Dao
interface VideoDao {
    @Query("SELECT * FROM videos ORDER BY dateModified DESC, title COLLATE NOCASE ASC, path ASC")
    fun observeByModifiedDate(): Flow<List<VideoEntity>>

    @Query("SELECT * FROM videos ORDER BY title COLLATE NOCASE ASC, path ASC")
    fun observeByNameAscending(): Flow<List<VideoEntity>>

    @Query("SELECT * FROM videos ORDER BY title COLLATE NOCASE DESC, path DESC")
    fun observeByNameDescending(): Flow<List<VideoEntity>>

    @Query("SELECT * FROM videos")
    suspend fun getAll(): List<VideoEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(videos: List<VideoEntity>)

    @Query("DELETE FROM videos WHERE path IN (:paths)")
    suspend fun deleteByPaths(paths: List<String>)
}

@Database(entities = [VideoEntity::class], version = 1, exportSchema = true)
abstract class VideoLibraryDatabase : RoomDatabase() {
    abstract fun videoDao(): VideoDao

    companion object {
        @Volatile
        private var instance: VideoLibraryDatabase? = null

        fun getInstance(context: Context): VideoLibraryDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    VideoLibraryDatabase::class.java,
                    "video-library.db"
                ).build().also { instance = it }
            }
    }
}
