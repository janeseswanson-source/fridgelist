package com.fridgelist.app.feature.onboarding

import com.fridgelist.core.data.auth.AuthRepository
import com.fridgelist.core.data.session.OnboardingStep
import com.fridgelist.core.data.session.SessionStore
import javax.inject.Inject

/** Where to go after any onboarding action: always recomputed from the stored session. */
class StepResolver @Inject constructor(
    private val auth: AuthRepository,
    private val session: SessionStore,
) {
    suspend fun next(): OnboardingStep = OnboardingStep.of(auth.currentUser() != null, session.current())
}
