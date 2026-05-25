package com.example

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "sync_history")
data class SyncHistoryRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val filename: String,
    val fileHash: String,
    val sizeBytes: Long,
    val direction: String, // "SENDER" or "RECEIVER"
    val targetDeviceName: String,
    val status: String, // "SUCCESS", "FAILED", "PARTIAL"
    val bytesTransferred: Long,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "paired_devices")
data class PairedDevice(
    @PrimaryKey val deviceId: String, // Unique device identification
    val deviceName: String,
    val lastKnownIp: String,
    val lastKnownPort: Int,
    val pinToken: String,
    val lastSyncTime: Long = System.currentTimeMillis()
)

@Dao
interface SyncHistoryDao {
    @Query("SELECT * FROM sync_history ORDER BY timestamp DESC")
    fun getAllRecords(): Flow<List<SyncHistoryRecord>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecord(record: SyncHistoryRecord)

    @Query("SELECT * FROM sync_history WHERE fileHash = :hash ORDER BY timestamp DESC LIMIT 1")
    suspend fun getRecordByHash(hash: String): SyncHistoryRecord?

    @Query("DELETE FROM sync_history")
    suspend fun clearHistory()
}

@Dao
interface PairedDeviceDao {
    @Query("SELECT * FROM paired_devices ORDER BY lastSyncTime DESC")
    fun getAllDevices(): Flow<List<PairedDevice>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDevice(device: PairedDevice)

    @Query("SELECT * FROM paired_devices WHERE deviceId = :id LIMIT 1")
    suspend fun getDeviceById(id: String): PairedDevice?

    @Delete
    suspend fun deleteDevice(device: PairedDevice)
}

@Database(entities = [SyncHistoryRecord::class, PairedDevice::class], version = 1, exportSchema = false)
abstract class SyncDatabase : RoomDatabase() {
    abstract fun syncHistoryDao(): SyncHistoryDao
    abstract fun pairedDeviceDao(): PairedDeviceDao

    companion object {
        @Volatile
        private var INSTANCE: SyncDatabase? = null

        fun getDatabase(context: Context): SyncDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    SyncDatabase::class.java,
                    "sync_database"
                )
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
