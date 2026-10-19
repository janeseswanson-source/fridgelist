package com.fridgelist.app.fakes

import com.fridgelist.app.feature.lists.HouseholdContextSource
import com.fridgelist.core.data.auth.AuthUser
import com.fridgelist.core.data.session.Session
import com.fridgelist.core.model.HouseholdDoc
import com.fridgelist.core.model.MemberDoc
import com.fridgelist.core.model.Role

/** "me" (Ayşe, item language tr) in household h1 with Mama as owner. */
fun householdContext(role: Role, session: FakeSession = FakeSession(Session("Ayşe", "tr", "h1", true))): HouseholdContextSource {
    val households = FakeHouseholds().apply {
        members.value = mapOf("h1" to listOf(MemberDoc("me", role, "Ayşe", "tr"), MemberDoc("mom", Role.OWNER, "Mama", "ar")))
        this.households.value = mapOf("h1" to HouseholdDoc("h1", "Ev", "mom", "ar"))
    }
    return HouseholdContextSource(session, FakeAuth(AuthUser("me", "Ayşe")), households)
}
