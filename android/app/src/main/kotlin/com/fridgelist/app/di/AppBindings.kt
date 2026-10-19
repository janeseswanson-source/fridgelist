package com.fridgelist.app.di

import com.fridgelist.app.feature.going.FusedLocationSource
import com.fridgelist.app.feature.going.LocationSource
import com.fridgelist.app.feature.onboarding.AppUiLanguageProvider
import com.fridgelist.app.feature.onboarding.UiLanguageProvider
import com.fridgelist.app.notifications.TripNotifications
import com.fridgelist.app.notifications.TripNotifier
import com.fridgelist.app.work.TripEnder
import com.fridgelist.app.work.WorkManagerTripEnder
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
interface AppBindings {
    @Binds fun uiLanguage(impl: AppUiLanguageProvider): UiLanguageProvider

    @Binds fun tripEnder(impl: WorkManagerTripEnder): TripEnder

    @Binds fun tripNotifier(impl: TripNotifications): TripNotifier

    @Binds fun location(impl: FusedLocationSource): LocationSource
}
