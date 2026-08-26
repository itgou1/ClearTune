package com.cleartune.app.di

import android.content.Context
import com.cleartune.core.database.ClearTuneDatabase
import com.cleartune.core.database.DatabaseFactory
import com.cleartune.core.datastore.CredentialsStore
import com.cleartune.core.datastore.PlaybackPreferences
import com.cleartune.core.datastore.AppPreferences
import com.cleartune.core.network.OpenSubsonicClient
import com.cleartune.core.network.OpenSubsonicApiFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun provideCredentialsStore(
        @ApplicationContext context: Context,
    ): CredentialsStore = CredentialsStore(context)

    @Provides
    @Singleton
    fun providePlaybackPreferences(
        @ApplicationContext context: Context,
    ): PlaybackPreferences = PlaybackPreferences(context)

    @Provides
    @Singleton
    fun provideAppPreferences(
        @ApplicationContext context: Context,
    ): AppPreferences = AppPreferences(context)

    @Provides
    @Singleton
    fun provideOpenSubsonicClient(): OpenSubsonicClient = OpenSubsonicClient()

    @Provides
    @Singleton
    fun provideOpenSubsonicApiFactory(): OpenSubsonicApiFactory = OpenSubsonicApiFactory()

    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
    ): ClearTuneDatabase = DatabaseFactory.create(context)
}
