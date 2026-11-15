package com.tubepilot.core.di

import com.tubepilot.core.security.KeystoreSecretStore
import com.tubepilot.core.security.SecretStore
import com.tubepilot.core.util.SystemTimeSource
import com.tubepilot.core.util.TimeSource
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

/** Process-wide scope for fire-and-forget work that must outlive a screen (e.g. audit writes). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
abstract class CoreModule {
    @Binds abstract fun secretStore(impl: KeystoreSecretStore): SecretStore
    @Binds abstract fun timeSource(impl: SystemTimeSource): TimeSource

    companion object {
        @Provides
        @Singleton
        @ApplicationScope
        fun applicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        /**
         * Shared HTTP client. Long read timeouts accommodate streamed LLM responses and chunked
         * uploads; individual clients derive from it with newBuilder() to share the pool.
         */
        @Provides
        @Singleton
        fun okHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.MINUTES)
            .writeTimeout(5, TimeUnit.MINUTES)
            .retryOnConnectionFailure(true)
            .build()
    }
}
