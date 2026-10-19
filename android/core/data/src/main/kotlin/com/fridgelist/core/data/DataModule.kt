package com.fridgelist.core.data

import com.fridgelist.core.data.auth.AuthRepository
import com.fridgelist.core.data.auth.FirebaseAuthRepository
import com.fridgelist.core.data.callable.CallableClient
import com.fridgelist.core.data.callable.FirebaseCallableClient
import com.fridgelist.core.data.device.DeviceRegistrar
import com.fridgelist.core.data.device.DeviceRegistry
import com.fridgelist.core.data.device.FirebasePushTokenProvider
import com.fridgelist.core.data.device.PushTokenProvider
import com.fridgelist.core.data.household.FirebaseHouseholdRepository
import com.fridgelist.core.data.household.HouseholdRepository
import com.fridgelist.core.data.items.AliasRepository
import com.fridgelist.core.data.items.FirestoreItemsData
import com.fridgelist.core.data.items.ItemRepository
import com.fridgelist.core.data.items.ListRepository
import com.fridgelist.core.data.items.ParseRepository
import com.fridgelist.core.data.session.DataStoreSessionStore
import com.fridgelist.core.data.session.SessionStore
import com.fridgelist.core.data.user.FirestoreUserRepository
import com.fridgelist.core.data.trips.FirebaseTripRepository
import com.fridgelist.core.data.trips.TripRepository
import com.fridgelist.core.data.user.UserRepository
import com.fridgelist.core.data.voice.CallableTranscribeRepository
import com.fridgelist.core.data.voice.TranscribeRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
interface DataModule {
    @Binds fun callables(impl: FirebaseCallableClient): CallableClient

    @Binds fun auth(impl: FirebaseAuthRepository): AuthRepository

    @Binds fun households(impl: FirebaseHouseholdRepository): HouseholdRepository

    @Binds fun users(impl: FirestoreUserRepository): UserRepository

    @Binds fun session(impl: DataStoreSessionStore): SessionStore

    @Binds fun pushTokens(impl: FirebasePushTokenProvider): PushTokenProvider

    @Binds fun devices(impl: DeviceRegistrar): DeviceRegistry

    @Binds fun lists(impl: FirestoreItemsData): ListRepository

    @Binds fun items(impl: FirestoreItemsData): ItemRepository

    @Binds fun aliases(impl: FirestoreItemsData): AliasRepository

    @Binds fun parse(impl: FirestoreItemsData): ParseRepository

    @Binds fun transcribe(impl: CallableTranscribeRepository): TranscribeRepository

    @Binds fun trips(impl: FirebaseTripRepository): TripRepository
}
