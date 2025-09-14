package com.filetransfer.minimal

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Dao
interface TransferStateDao {
    @Query("SELECT * FROM transfer_states WHERE status IN (:statuses)")
    fun getTransfersByStatus(statuses: List<TransferStatus>): Flow<List<TransferState>>

    @Query("SELECT * FROM transfer_states WHERE transferId = :transferId")
    suspend fun getTransferById(transferId: String): TransferState?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTransfer(transfer: TransferState)

    @Update
    suspend fun updateTransfer(transfer: TransferState)

    @Delete
    suspend fun deleteTransfer(transfer: TransferState)

    @Query("DELETE FROM transfer_states WHERE status = :status")
    suspend fun deleteTransfersByStatus(status: TransferStatus)

    @Query("SELECT * FROM transfer_states ORDER BY timestamp DESC")
    fun getAllTransfers(): Flow<List<TransferState>>

    @Query("DELETE FROM transfer_states WHERE timestamp < :cutoffTime")
    suspend fun deleteOldTransfers(cutoffTime: Long)
}

@Database(
    entities = [TransferState::class],
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class TransferDatabase : RoomDatabase() {
    abstract fun transferStateDao(): TransferStateDao

    companion object {
        @Volatile
        private var INSTANCE: TransferDatabase? = null

        fun getDatabase(context: android.content.Context): TransferDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    TransferDatabase::class.java,
                    "transfer_database"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}

class Converters {
    @TypeConverter
    fun fromIntSet(value: Set<Int>): String {
        return value.joinToString(",")
    }

    @TypeConverter
    fun toIntSet(value: String): Set<Int> {
        return if (value.isEmpty()) emptySet()
        else value.split(",").map { it.toInt() }.toSet()
    }

    @TypeConverter
    fun fromTransferStatus(status: TransferStatus): String {
        return status.name
    }

    @TypeConverter
    fun toTransferStatus(status: String): TransferStatus {
        return TransferStatus.valueOf(status)
    }
}
