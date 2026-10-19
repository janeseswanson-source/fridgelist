package com.fridgelist.app.di

import com.fridgelist.app.feature.going.FusedLocationSource
import com.fridgelist.app.feature.going.LocationSource
import com.fridgelist.app.feature.onboarding.AppUiLanguageProvider
import com.fridgelist.app.feature.settings.AndroidLocationPermissionState
import com.fridgelist.app.feature.settings.LocationPermissionState
import com.fridgelist.app.location.DetectionNotifications
import com.fridgelist.app.location.DetectionNotifier
import com.fridgelist.app.location.StoreFences
import com.fridgelist.app.location.StoreGeofenceManager
import com.fridgelist.app.widget.WidgetCleaner
import com.fridgelist.app.widget.WidgetRefresher
import com.fridgelist.app.widget.WidgetSnapshotWriter
import com.fridgelist.app.widget.WorkManagerWidgetRefresher
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

    @Binds fun storeFences(impl: StoreGeofenceManager): StoreFences

    @Binds fun detectionNotifier(impl: DetectionNotifications): DetectionNotifier

    @Binds fun locationPermissions(impl: AndroidLocationPermissionState): LocationPermissionState

    @Binds fun widgetRefresher(impl: WorkManagerWidgetRefresher): WidgetRefresher

    @Binds fun widgetCleaner(impl: WidgetSnapshotWriter): WidgetCleaner
}
