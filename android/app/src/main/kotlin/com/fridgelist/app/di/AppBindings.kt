package com.fridgelist.app.di

import com.fridgelist.app.feature.onboarding.AppUiLanguageProvider
import com.fridgelist.app.feature.onboarding.UiLanguageProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
interface AppBindings {
    @Binds fun uiLanguage(impl: AppUiLanguageProvider): UiLanguageProvider
}
