package com.aitrader.di

import com.aitrader.data.repo.TradingRepository
import com.aitrader.work.Notifier
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Access for components Hilt can't inject directly (Glance widget, WorkManager worker). */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface AppEntryPoint {
    fun repository(): TradingRepository
    fun notifier(): Notifier
}
