package com.fridgelist.core.data.session

/** Where the app should open (PRODUCT_SPEC F1, prompt A2 flow). Resumes after a restart. */
enum class OnboardingStep {
    /** UI language, then sign-in. */
    WELCOME,

    /** Display name and item language. */
    PROFILE,

    /** Create or join a household. */
    HOUSEHOLD,

    /** Notification rationale and permission. */
    NOTIFICATIONS,

    DONE,
    ;

    companion object {
        fun of(signedIn: Boolean, session: Session): OnboardingStep = when {
            !signedIn -> WELCOME
            session.profileName.isNullOrBlank() || session.itemLanguage.isNullOrBlank() -> PROFILE
            session.householdId.isNullOrBlank() -> HOUSEHOLD
            !session.notificationsAsked -> NOTIFICATIONS
            else -> DONE
        }
    }
}
