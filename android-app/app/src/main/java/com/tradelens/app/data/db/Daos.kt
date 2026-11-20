package com.tradelens.app.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface InstrumentDao {
    @Upsert suspend fun upsertAll(items: List<InstrumentEntity>)
    @Query("DELETE FROM instruments WHERE broker = :broker") suspend fun clear(broker: String)
    @Query("SELECT * FROM instruments WHERE (symbol LIKE :q || '%' OR name LIKE '%' || :q || '%') ORDER BY CASE WHEN segment IN ('EQUITY','INDEX') THEN 0 ELSE 1 END, length(symbol) LIMIT :limit")
    suspend fun search(q: String, limit: Int = 50): List<InstrumentEntity>
    @Query("SELECT * FROM instruments WHERE broker = :broker AND exchange = :exchange AND symbol = :symbol LIMIT 1")
    suspend fun find(broker: String, exchange: String, symbol: String): InstrumentEntity?
    @Query("SELECT * FROM instruments WHERE broker = :broker AND underlying = :underlying AND segment = 'OPTIONS' AND expiry = :expiry")
    suspend fun options(broker: String, underlying: String, expiry: Long): List<InstrumentEntity>
    @Query("SELECT DISTINCT expiry FROM instruments WHERE broker = :broker AND underlying = :underlying AND segment = 'OPTIONS' AND expiry >= :from ORDER BY expiry LIMIT 12")
    suspend fun expiries(broker: String, underlying: String, from: Long): List<Long>
    @Query("SELECT COUNT(*) FROM instruments WHERE broker = :broker") suspend fun count(broker: String): Int
}

@Dao
interface CandleDao {
    @Upsert suspend fun upsert(items: List<CandleEntity>)
    @Query("SELECT * FROM candles WHERE instrumentKey = :key AND tf = :tf ORDER BY time DESC LIMIT :limit")
    suspend fun latest(key: String, tf: String, limit: Int): List<CandleEntity>
    @Query("DELETE FROM candles WHERE time < :before AND tf IN ('1m','5m')") suspend fun pruneIntraday(before: Long)
}

@Dao
interface WatchlistDao {
    @Query("SELECT * FROM watchlists ORDER BY position, id") fun lists(): Flow<List<WatchlistEntity>>
    @Query("SELECT * FROM watchlist_items WHERE listId = :listId ORDER BY position, id") fun items(listId: Long): Flow<List<WatchlistItemEntity>>
    @Query("SELECT * FROM watchlist_items") suspend fun allItems(): List<WatchlistItemEntity>
    @Insert suspend fun insertList(l: WatchlistEntity): Long
    @Query("DELETE FROM watchlists WHERE id = :id") suspend fun deleteList(id: Long)
    @Query("DELETE FROM watchlist_items WHERE listId = :id") suspend fun clearList(id: Long)
    @Insert suspend fun insertItem(i: WatchlistItemEntity): Long
    @Delete suspend fun deleteItem(i: WatchlistItemEntity)
    @Query("SELECT COUNT(*) FROM watchlists") suspend fun count(): Int
}

@Dao
interface PaperDao {
    @Query("SELECT * FROM paper_account WHERE id = 1") suspend fun account(): PaperAccountEntity?
    @Query("SELECT * FROM paper_account WHERE id = 1") fun accountFlow(): Flow<PaperAccountEntity?>
    @Upsert suspend fun saveAccount(a: PaperAccountEntity)
    @Insert suspend fun insertOrder(o: PaperOrderEntity): Long
    @Upsert suspend fun updateOrder(o: PaperOrderEntity)
    @Query("SELECT * FROM paper_orders ORDER BY createdAt DESC LIMIT 200") fun orders(): Flow<List<PaperOrderEntity>>
    @Query("SELECT * FROM paper_orders WHERE status = 'OPEN'") suspend fun openOrders(): List<PaperOrderEntity>
    @Query("SELECT * FROM paper_positions") fun positions(): Flow<List<PaperPositionEntity>>
    @Query("SELECT * FROM paper_positions") suspend fun positionsNow(): List<PaperPositionEntity>
    @Upsert suspend fun savePosition(p: PaperPositionEntity)
    @Query("DELETE FROM paper_positions WHERE instrumentKey = :key") suspend fun deletePosition(key: String)
    @Insert suspend fun insertTrade(t: PaperTradeEntity)
    @Query("SELECT * FROM paper_trades ORDER BY closedAt DESC") fun trades(): Flow<List<PaperTradeEntity>>
    @Query("UPDATE paper_trades SET notes = :notes WHERE id = :id") suspend fun setNotes(id: Long, notes: String)
    @Query("DELETE FROM paper_orders") suspend fun clearOrders()
    @Query("DELETE FROM paper_positions") suspend fun clearPositions()
    @Query("DELETE FROM paper_trades") suspend fun clearTrades()

    @Transaction
    suspend fun reset(capital: Double, now: Long) {
        clearOrders(); clearPositions(); clearTrades()
        saveAccount(PaperAccountEntity(1, capital, capital, capital, capital, now))
    }
}

@Dao
interface AlertDao {
    @Query("SELECT * FROM alerts ORDER BY createdAt DESC") fun all(): Flow<List<AlertEntity>>
    @Query("SELECT * FROM alerts WHERE active = 1") suspend fun active(): List<AlertEntity>
    @Upsert suspend fun save(a: AlertEntity): Long
    @Query("DELETE FROM alerts WHERE id = :id") suspend fun delete(id: Long)
}

@Dao
interface AuditDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun log(e: AuditEntity)
    @Query("SELECT * FROM audit_log ORDER BY time DESC LIMIT :limit") fun recent(limit: Int = 500): Flow<List<AuditEntity>>
    @Query("SELECT * FROM audit_log ORDER BY time DESC") suspend fun all(): List<AuditEntity>
}

@Dao
interface ScreenDao {
    @Query("SELECT * FROM saved_screens ORDER BY name") fun all(): Flow<List<SavedScreenEntity>>
    @Upsert suspend fun save(s: SavedScreenEntity)
    @Query("DELETE FROM saved_screens WHERE id = :id") suspend fun delete(id: Long)
}

@Database(
    entities = [
        InstrumentEntity::class, CandleEntity::class, WatchlistEntity::class, WatchlistItemEntity::class,
        PaperOrderEntity::class, PaperPositionEntity::class, PaperTradeEntity::class, PaperAccountEntity::class,
        AlertEntity::class, AuditEntity::class, SavedScreenEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun instruments(): InstrumentDao
    abstract fun candles(): CandleDao
    abstract fun watchlists(): WatchlistDao
    abstract fun paper(): PaperDao
    abstract fun alerts(): AlertDao
    abstract fun audit(): AuditDao
    abstract fun screens(): ScreenDao
}
