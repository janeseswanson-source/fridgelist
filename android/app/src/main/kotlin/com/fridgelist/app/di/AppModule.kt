package com.fridgelist.app.di

import com.fridgelist.app.BuildConfig
import com.fridgelist.core.data.firebase.AppEnvironment
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    /** `dev` debug builds → emulators; `prod`, and every release build, never (CLAUDE.md "Emulator mode"). */
    @Provides
    @Singleton
    fun environment(): AppEnvironment = AppEnvironment(
        useEmulators = BuildConfig.USE_EMULATORS && BuildConfig.DEBUG,
        appVersion = BuildConfig.VERSION_NAME,
        linkDomain = BuildConfig.LINK_DOMAIN,
        googleWebClientId = BuildConfig.GOOGLE_WEB_CLIENT_ID,
    )
}
