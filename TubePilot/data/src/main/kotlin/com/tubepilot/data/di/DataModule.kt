package com.tubepilot.data.di

import android.content.Context
import androidx.room.Room
import com.tubepilot.core.domain.AgentStatusRepository
import com.tubepilot.core.domain.AuditLog
import com.tubepilot.core.domain.CalendarRepository
import com.tubepilot.core.domain.CommentRepository
import com.tubepilot.core.domain.KeyValueStore
import com.tubepilot.core.domain.MemoryRepository
import com.tubepilot.core.domain.ProjectRepository
import com.tubepilot.core.domain.QuotaRepository
import com.tubepilot.core.domain.ReportRepository
import com.tubepilot.core.domain.SettingsRepository
import com.tubepilot.core.domain.SkillStateRepository
import com.tubepilot.core.domain.UsageRepository
import com.tubepilot.data.db.TubePilotDatabase
import com.tubepilot.data.repo.RoomAgentStatusRepository
import com.tubepilot.data.repo.RoomAuditLog
import com.tubepilot.data.repo.RoomCalendarRepository
import com.tubepilot.data.repo.RoomCommentRepository
import com.tubepilot.data.repo.RoomKeyValueStore
import com.tubepilot.data.repo.RoomMemoryRepository
import com.tubepilot.data.repo.RoomProjectRepository
import com.tubepilot.data.repo.RoomQuotaRepository
import com.tubepilot.data.repo.RoomReportRepository
import com.tubepilot.data.repo.RoomSkillStateRepository
import com.tubepilot.data.repo.RoomUsageRepository
import com.tubepilot.data.settings.DataStoreSettingsRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DataModule {
    @Binds abstract fun settings(impl: DataStoreSettingsRepository): SettingsRepository
    @Binds abstract fun memory(impl: RoomMemoryRepository): MemoryRepository
    @Binds abstract fun projects(impl: RoomProjectRepository): ProjectRepository
    @Binds abstract fun calendar(impl: RoomCalendarRepository): CalendarRepository
    @Binds abstract fun audit(impl: RoomAuditLog): AuditLog
    @Binds abstract fun usage(impl: RoomUsageRepository): UsageRepository
    @Binds abstract fun quota(impl: RoomQuotaRepository): QuotaRepository
    @Binds abstract fun comments(impl: RoomCommentRepository): CommentRepository
    @Binds abstract fun agentStatus(impl: RoomAgentStatusRepository): AgentStatusRepository
    @Binds abstract fun skillState(impl: RoomSkillStateRepository): SkillStateRepository
    @Binds abstract fun reports(impl: RoomReportRepository): ReportRepository
    @Binds abstract fun keyValue(impl: RoomKeyValueStore): KeyValueStore

    companion object {
        @Provides
        @Singleton
        fun database(@ApplicationContext context: Context): TubePilotDatabase =
            Room.databaseBuilder(context, TubePilotDatabase::class.java, TubePilotDatabase.NAME)
                // Schema v1 is the first release; future versions add explicit Migration objects.
                .build()
    }
}
