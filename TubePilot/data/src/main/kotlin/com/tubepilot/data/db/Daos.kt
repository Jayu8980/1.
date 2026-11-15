package com.tubepilot.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface KeyValueDao {
    @Query("SELECT value FROM kv WHERE `key` = :key")
    suspend fun get(key: String): String?

    @Query("SELECT value FROM kv WHERE `key` = :key")
    fun observe(key: String): Flow<String?>

    @Upsert
    suspend fun put(entity: KeyValueEntity)
}

@Dao
interface LessonDao {
    @Query("SELECT * FROM lessons ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<LessonEntity>>

    @Query("SELECT * FROM lessons WHERE active = 1 ORDER BY confidence DESC, createdAt DESC LIMIT :limit")
    suspend fun active(limit: Int): List<LessonEntity>

    @Query("SELECT * FROM lessons WHERE active = 1 AND category IN (:categories) ORDER BY confidence DESC, createdAt DESC LIMIT :limit")
    suspend fun activeIn(categories: List<String>, limit: Int): List<LessonEntity>

    @Insert
    suspend fun insert(entity: LessonEntity): Long

    @Query("UPDATE lessons SET active = :active WHERE id = :id")
    suspend fun setActive(id: Long, active: Boolean)
}

@Dao
interface PerformanceDao {
    @Query("SELECT * FROM performance ORDER BY publishedAt DESC")
    fun observeAll(): Flow<List<PerformanceEntity>>

    @Query("SELECT * FROM performance ORDER BY publishedAt DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<PerformanceEntity>

    @Query("SELECT * FROM performance WHERE publishedAt >= :since ORDER BY publishedAt DESC")
    suspend fun since(since: Long): List<PerformanceEntity>

    @Upsert
    suspend fun upsert(items: List<PerformanceEntity>)
}

@Dao
interface ProjectDao {
    @Query("SELECT * FROM projects ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects WHERE id = :id")
    fun observe(id: String): Flow<ProjectEntity?>

    @Query("SELECT * FROM projects WHERE id = :id")
    suspend fun get(id: String): ProjectEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: ProjectEntity)

    @Upsert
    suspend fun upsert(entity: ProjectEntity)

    @Query("SELECT * FROM projects WHERE status NOT IN ('DONE','FAILED','REJECTED') ORDER BY createdAt ASC")
    suspend fun inProgress(): List<ProjectEntity>

    @Query("SELECT * FROM projects ORDER BY createdAt DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<ProjectEntity>

    @Query("SELECT * FROM projects WHERE status IN ('SCHEDULED','PUBLISHED','DONE') AND updatedAt >= :since ORDER BY updatedAt DESC")
    suspend fun publishedSince(since: Long): List<ProjectEntity>

    @Query("DELETE FROM projects WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface CalendarDao {
    @Query("SELECT * FROM calendar WHERE slotStart >= :from AND slotStart < :to ORDER BY slotStart")
    fun observeRange(from: Long, to: Long): Flow<List<CalendarEntity>>

    @Query("SELECT * FROM calendar WHERE slotStart >= :from AND slotStart < :to ORDER BY slotStart")
    suspend fun between(from: Long, to: Long): List<CalendarEntity>

    @Query("SELECT * FROM calendar WHERE id = :id")
    suspend fun get(id: String): CalendarEntity?

    @Upsert
    suspend fun upsert(items: List<CalendarEntity>)

    @Query("DELETE FROM calendar WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface AuditDao {
    @Insert
    suspend fun insert(entity: AuditEntity)

    @Query("SELECT * FROM audit ORDER BY timestamp DESC, id DESC LIMIT :limit")
    fun observe(limit: Int): Flow<List<AuditEntity>>

    @Query("SELECT * FROM audit WHERE actor = :actor ORDER BY timestamp DESC, id DESC LIMIT :limit")
    fun observeActor(actor: String, limit: Int): Flow<List<AuditEntity>>

    @Query("SELECT * FROM audit WHERE projectId = :projectId ORDER BY timestamp DESC, id DESC LIMIT :limit")
    fun observeProject(projectId: String, limit: Int): Flow<List<AuditEntity>>

    /** Keeps the audit log bounded on the device; old entries are pruned, never edited. */
    @Query("DELETE FROM audit WHERE timestamp < :before")
    suspend fun pruneBefore(before: Long)
}

@Dao
interface UsageDao {
    @Insert
    suspend fun insert(entity: UsageEntity)

    @Query("SELECT COALESCE(SUM(costUsd), 0) FROM llm_usage WHERE timestamp >= :since")
    suspend fun costSince(since: Long): Double

    @Query("SELECT * FROM llm_usage WHERE timestamp >= :since")
    fun observeSince(since: Long): Flow<List<UsageEntity>>
}

@Dao
interface QuotaDao {
    @Insert
    suspend fun insert(entity: QuotaEntity)

    @Query("SELECT bucket, SUM(units) AS units FROM quota_usage WHERE dayKey = :dayKey GROUP BY bucket")
    suspend fun usedOn(dayKey: String): List<BucketUnits>

    @Query("SELECT bucket, SUM(units) AS units FROM quota_usage WHERE dayKey = :dayKey GROUP BY bucket")
    fun observeDay(dayKey: String): Flow<List<BucketUnits>>
}

data class BucketUnits(val bucket: String, val units: Int)

@Dao
interface CommentDao {
    @Query("SELECT * FROM comments ORDER BY publishedAt DESC LIMIT 500")
    fun observeAll(): Flow<List<CommentEntity>>

    @Query("SELECT * FROM comments WHERE commentId = :id")
    suspend fun get(id: String): CommentEntity?

    @Query("SELECT commentId FROM comments WHERE commentId IN (:ids)")
    suspend fun existing(ids: List<String>): List<String>

    @Upsert
    suspend fun upsert(items: List<CommentEntity>)

    @Query("SELECT * FROM comments WHERE status = :status ORDER BY publishedAt ASC LIMIT :limit")
    suspend fun byStatus(status: String, limit: Int): List<CommentEntity>
}

@Dao
interface AgentStatusDao {
    @Query("SELECT * FROM agent_status")
    fun observeAll(): Flow<List<AgentStatusEntity>>

    @Upsert
    suspend fun upsert(entity: AgentStatusEntity)
}

@Dao
interface SkillDao {
    @Query("SELECT * FROM skill_state")
    fun observeStates(): Flow<List<SkillStateEntity>>

    @Query("SELECT * FROM skill_state")
    suspend fun states(): List<SkillStateEntity>

    @Upsert
    suspend fun upsertState(entity: SkillStateEntity)

    @Query("SELECT * FROM imported_skills ORDER BY importedAt DESC")
    fun observeImported(): Flow<List<ImportedSkillEntity>>

    @Query("SELECT * FROM imported_skills ORDER BY importedAt DESC")
    suspend fun imported(): List<ImportedSkillEntity>

    @Upsert
    suspend fun upsertImported(entity: ImportedSkillEntity)

    @Transaction
    suspend fun deleteImportedAndState(id: String) {
        deleteImported(id)
        deleteStates(id)
    }

    @Query("DELETE FROM imported_skills WHERE id = :id")
    suspend fun deleteImported(id: String)

    @Query("DELETE FROM skill_state WHERE skillId = :id")
    suspend fun deleteStates(id: String)

    @Insert
    suspend fun insertCall(entity: SkillCallEntity)

    @Query("SELECT * FROM skill_calls ORDER BY timestamp DESC LIMIT :limit")
    fun observeCalls(limit: Int): Flow<List<SkillCallEntity>>
}

@Dao
interface ReportDao {
    @Insert
    suspend fun insert(entity: WeeklyReportEntity)

    @Query("SELECT * FROM weekly_reports ORDER BY weekStart DESC, createdAt DESC LIMIT 1")
    fun observeLatest(): Flow<WeeklyReportEntity?>

    @Query("SELECT * FROM weekly_reports ORDER BY weekStart DESC, createdAt DESC LIMIT 52")
    fun observeAll(): Flow<List<WeeklyReportEntity>>
}
