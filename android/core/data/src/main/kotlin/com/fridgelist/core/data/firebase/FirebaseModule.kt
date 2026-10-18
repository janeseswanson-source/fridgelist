package com.fridgelist.core.data.firebase

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Firebase entry points. Each provider makes sure [FirebaseBootstrap] ran first. */
@Module
@InstallIn(SingletonComponent::class)
object FirebaseModule {

    @Provides
    @Singleton
    fun auth(bootstrap: FirebaseBootstrap): FirebaseAuth {
        bootstrap.ensureInitialized()
        return FirebaseAuth.getInstance()
    }

    @Provides
    @Singleton
    fun firestore(bootstrap: FirebaseBootstrap): FirebaseFirestore {
        bootstrap.ensureInitialized()
        return FirebaseFirestore.getInstance()
    }

    @Provides
    @Singleton
    fun functions(bootstrap: FirebaseBootstrap): FirebaseFunctions {
        bootstrap.ensureInitialized()
        return FirebaseFunctions.getInstance(FUNCTIONS_REGION)
    }
}
