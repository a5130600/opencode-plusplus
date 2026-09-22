package com.opencode.mobile.di

import android.content.Context
import androidx.room.Room
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.opencode.mobile.core.DefaultDispatcher
import com.opencode.mobile.core.IoDispatcher
import com.opencode.mobile.data.local.CachedFileDao
import com.opencode.mobile.data.local.DeviceDao
import com.opencode.mobile.data.local.MessageDao
import com.opencode.mobile.data.local.OpenCodeDatabase
import com.opencode.mobile.data.local.SessionDao
import com.opencode.mobile.data.local.WorkspaceDao
import com.opencode.mobile.data.remote.BasicAuthInterceptor
import com.opencode.mobile.data.remote.DiNames
import com.opencode.mobile.data.remote.EndpointInterceptor
import com.opencode.mobile.data.remote.HttpClients
import com.opencode.mobile.data.remote.JsonFactory
import com.opencode.mobile.data.remote.OpenCodeApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object CoreModule {

    @Provides
    @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @DefaultDispatcher
    fun provideDefaultDispatcher(): CoroutineDispatcher = Dispatchers.Default

    @Provides
    @Singleton
    fun provideJson(): Json = JsonFactory.INSTANCE

    /**
     * 生命周期长于任何 ViewModel 的作用域。
     * 必须用 SupervisorJob：SSE 挂掉不应该连坐缓存清理这类无关任务。
     */
    @Provides
    @Singleton
    @Named(DiNames.APPLICATION_SCOPE)
    fun provideApplicationScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
}

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideLogging(): HttpLoggingInterceptor =
        HttpLoggingInterceptor().apply {
            // BASIC 只打请求行，不碰 headers / body —— 避免把 Authorization 和代码内容写进日志
            level = HttpLoggingInterceptor.Level.BASIC
        }

    @Provides
    @Singleton
    @Named(DiNames.REST_CLIENT)
    fun provideRestClient(
        endpoint: EndpointInterceptor,
        auth: BasicAuthInterceptor,
        logging: HttpLoggingInterceptor,
    ): OkHttpClient = HttpClients.rest(endpoint, auth, logging)

    @Provides
    @Singleton
    @Named(DiNames.STREAMING_CLIENT)
    fun provideStreamingClient(
        endpoint: EndpointInterceptor,
        auth: BasicAuthInterceptor,
    ): OkHttpClient = HttpClients.streaming(endpoint, auth)

    @Provides
    @Singleton
    fun provideRetrofit(@Named(DiNames.REST_CLIENT) client: OkHttpClient): Retrofit =
        Retrofit.Builder()
            // 占位 baseUrl：真实主机由 EndpointInterceptor 在发请求前重写。
            // 这样一套 Retrofit 就能服务所有设备，切设备不必重建。
            .baseUrl("http://localhost/")
            .client(client)
            .addConverterFactory(JsonFactory.INSTANCE.asConverterFactory(JsonFactory.CONTENT_TYPE))
            .build()

    @Provides
    @Singleton
    fun provideApi(retrofit: Retrofit): OpenCodeApi = retrofit.create(OpenCodeApi::class.java)
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): OpenCodeDatabase =
        Room.databaseBuilder(context, OpenCodeDatabase::class.java, "opencode.db")
            .fallbackToDestructiveMigration()
            .build()

    @Provides fun provideDeviceDao(db: OpenCodeDatabase): DeviceDao = db.deviceDao()
    @Provides fun provideSessionDao(db: OpenCodeDatabase): SessionDao = db.sessionDao()
    @Provides fun provideMessageDao(db: OpenCodeDatabase): MessageDao = db.messageDao()
    @Provides fun provideWorkspaceDao(db: OpenCodeDatabase): WorkspaceDao = db.workspaceDao()
    @Provides fun provideCachedFileDao(db: OpenCodeDatabase): CachedFileDao = db.cachedFileDao()
}
