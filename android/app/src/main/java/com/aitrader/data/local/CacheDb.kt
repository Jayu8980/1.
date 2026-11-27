package com.aitrader.data.local

import androidx.room.*

/** Last good server response per key, so every screen can render offline. */
@Entity(tableName = "cache")
data class CacheEntity(@PrimaryKey val key: String, val json: String, val updatedAt: Long)

@Entity(tableName = "alerts")
data class AlertEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ts: Long, val kind: String, val title: String, val body: String,
)

@Dao
interface CacheDao {
    @Query("SELECT * FROM cache WHERE `key` = :key") suspend fun get(key: String): CacheEntity?
    @Upsert suspend fun put(e: CacheEntity)

    @Query("SELECT * FROM alerts ORDER BY ts DESC LIMIT 200") fun alerts(): kotlinx.coroutines.flow.Flow<List<AlertEntity>>
    @Insert suspend fun addAlert(a: AlertEntity)
}

@Database(entities = [CacheEntity::class, AlertEntity::class], version = 1, exportSchema = true)
abstract class CacheDb : RoomDatabase() {
    abstract fun dao(): CacheDao
}
