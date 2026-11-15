package com.tubepilot.youtube.di

import com.tubepilot.core.error.AuthRequiredException
import com.tubepilot.core.util.TubeJson
import com.tubepilot.youtube.api.YouTubeAnalyticsService
import com.tubepilot.youtube.api.YouTubeDataService
import com.tubepilot.youtube.auth.YouTubeAuthManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.Route
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.IOException
import javax.inject.Qualifier
import javax.inject.Singleton

/** OkHttp client that attaches the YouTube OAuth bearer token. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AuthorizedClient

/** Wraps auth failures as IOException so OkHttp/Retrofit propagate them, then unwraps upstream. */
class AuthIoException(val auth: AuthRequiredException) : IOException(auth.message, auth)

@Module
@InstallIn(SingletonComponent::class)
object YouTubeModule {

    @Provides
    @Singleton
    @AuthorizedClient
    fun authorizedClient(base: OkHttpClient, auth: YouTubeAuthManager): OkHttpClient {
        // Interceptors run on OkHttp worker threads, so blocking for the token is safe here.
        val tokenInterceptor = Interceptor { chain ->
            val token = try {
                runBlocking { auth.accessToken() }
            } catch (e: AuthRequiredException) {
                throw AuthIoException(e)
            }
            chain.proceed(chain.request().newBuilder().header("Authorization", "Bearer $token").build())
        }
        // On 401, drop the cached token and retry once with a fresh one.
        val authenticator = Authenticator { _: Route?, response: Response ->
            if (response.priorResponse != null) return@Authenticator null
            val stale = response.request.header("Authorization")?.removePrefix("Bearer ")
            runBlocking {
                stale?.let { auth.invalidate(it) }
                runCatching { auth.accessToken() }.getOrNull()
            }?.let { fresh -> response.request.newBuilder().header("Authorization", "Bearer $fresh").build() }
        }
        return base.newBuilder()
            .addInterceptor(tokenInterceptor)
            .authenticator(authenticator)
            .build()
    }

    @Provides
    @Singleton
    fun dataService(@AuthorizedClient client: OkHttpClient): YouTubeDataService = Retrofit.Builder()
        .baseUrl("https://www.googleapis.com/youtube/v3/")
        .client(client)
        .addConverterFactory(TubeJson.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(YouTubeDataService::class.java)

    @Provides
    @Singleton
    fun analyticsService(@AuthorizedClient client: OkHttpClient): YouTubeAnalyticsService = Retrofit.Builder()
        .baseUrl("https://youtubeanalytics.googleapis.com/v2/")
        .client(client)
        .addConverterFactory(TubeJson.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(YouTubeAnalyticsService::class.java)
}
