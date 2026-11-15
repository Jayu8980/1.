package com.tubepilot.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        KeyValueEntity::class,
        LessonEntity::class,
        PerformanceEntity::class,
        ProjectEntity::class,
        CalendarEntity::class,
        AuditEntity::class,
        UsageEntity::class,
        QuotaEntity::class,
        CommentEntity::class,
        AgentStatusEntity::class,
        SkillStateEntity::class,
        ImportedSkillEntity::class,
        SkillCallEntity::class,
        WeeklyReportEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class TubePilotDatabase : RoomDatabase() {
    abstract fun keyValueDao(): KeyValueDao
    abstract fun lessonDao(): LessonDao
    abstract fun performanceDao(): PerformanceDao
    abstract fun projectDao(): ProjectDao
    abstract fun calendarDao(): CalendarDao
    abstract fun auditDao(): AuditDao
    abstract fun usageDao(): UsageDao
    abstract fun quotaDao(): QuotaDao
    abstract fun commentDao(): CommentDao
    abstract fun agentStatusDao(): AgentStatusDao
    abstract fun skillDao(): SkillDao
    abstract fun reportDao(): ReportDao

    companion object {
        const val NAME = "tubepilot.db"
    }
}
