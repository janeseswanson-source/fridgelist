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
    /** `dev` flavor → emulators; `prod` → never (CLAUDE.md "Emulator mode"). */
    @Provides
    @Singleton
    fun environment(): AppEnvironment = AppEnvironment(useEmulators = BuildConfig.USE_EMULATORS)
}
