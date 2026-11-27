package com.aitrader.di

import android.content.Context
import androidx.room.Room
import com.aitrader.data.local.CacheDao
import com.aitrader.data.local.CacheDb
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides @Singleton
    fun json(): Json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

    @Provides @Singleton
    fun db(@ApplicationContext ctx: Context): CacheDb =
        Room.databaseBuilder(ctx, CacheDb::class.java, "cache.db").fallbackToDestructiveMigration(true).build()

    @Provides fun dao(db: CacheDb): CacheDao = db.dao()
}
