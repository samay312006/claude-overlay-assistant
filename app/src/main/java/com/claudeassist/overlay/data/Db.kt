package com.claudeassist.overlay.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "history")
data class HistoryEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val question: String,
    val answer: String,
    val brainId: String,
    val timestampMs: Long,
    val thumbnailPath: String? = null,
)

@Entity(tableName = "telemetry_runs")
data class TelemetryRunEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val brainId: String,
    val startedAtMs: Long,
    val totalMs: Long,
    val success: Boolean,
    val failureReason: String? = null,
    val stepsJson: String,
)

@Dao
interface HistoryDao {
    @Insert
    suspend fun insert(entry: HistoryEntry): Long

    @Query("SELECT * FROM history ORDER BY timestampMs DESC LIMIT 200")
    fun observeRecent(): Flow<List<HistoryEntry>>

    @Query("DELETE FROM history")
    suspend fun clear()
}

@Dao
interface TelemetryDao {
    @Insert
    suspend fun insert(run: TelemetryRunEntity): Long

    @Query("SELECT * FROM telemetry_runs ORDER BY startedAtMs DESC LIMIT 100")
    fun observeRecent(): Flow<List<TelemetryRunEntity>>
}

@Database(entities = [HistoryEntry::class, TelemetryRunEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun historyDao(): HistoryDao
    abstract fun telemetryDao(): TelemetryDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "claude-assist.db")
                .fallbackToDestructiveMigration()
                .build()
    }
}
