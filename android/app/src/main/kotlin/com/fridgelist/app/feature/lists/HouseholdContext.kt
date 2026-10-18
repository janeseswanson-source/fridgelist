package com.fridgelist.app.feature.lists

import com.fridgelist.core.data.auth.AuthRepository
import com.fridgelist.core.data.household.HouseholdRepository
import com.fridgelist.core.data.session.SessionStore
import com.fridgelist.core.model.Languages
import com.fridgelist.core.model.MemberDoc
import com.fridgelist.core.model.Role
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
import java.util.Locale
import javax.inject.Inject

/** Everything a list screen needs to know about "me in this household". */
data class HouseholdContext(
    val householdId: String,
    val uid: String,
    val role: Role,
    /** Item display language (CONTRACT §1), the viewer side of the display rule. */
    val viewerLang: String,
    val defaultLanguage: String,
    val memberNames: Map<String, String> = emptyMap(),
) {
    /** BCP-47 hints for the splitter and `parseItems`: device locale first (it carries the region, e.g. ar-EG). */
    val localeHints: List<String>
        get() = listOf(Locale.getDefault().toLanguageTag(), viewerLang).distinct()
}

class HouseholdContextSource @Inject constructor(
    private val session: SessionStore,
    private val auth: AuthRepository,
    private val households: HouseholdRepository,
) {
    /**
     * Emits once the household and the first members snapshot are known (Firestore answers from
     * the offline cache, so this is near-instant offline too). Waiting matters: a provisional
     * role would turn an adult's adds into kid requests. A member missing from the list gets the
     * least privileged role, so the UI never offers an action the rules would reject.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val context: Flow<HouseholdContext> = combine(session.session, auth.user) { s, user -> Triple(s.householdId, user?.uid, s.itemLanguage) }
        .distinctUntilChanged()
        .flatMapLatest { (hid, uid, itemLanguage) ->
            if (hid == null || uid == null) return@flatMapLatest emptyFlow()
            combine(
                households.members(hid).catch { emit(emptyList()) },
                households.household(hid).onStart { emit(null) }.catch { emit(null) },
            ) { members, household ->
                HouseholdContext(
                    householdId = hid,
                    uid = uid,
                    role = members.roleOf(uid),
                    viewerLang = itemLanguage ?: household?.defaultLanguage ?: Languages.FALLBACK_UI,
                    defaultLanguage = household?.defaultLanguage ?: itemLanguage ?: Languages.FALLBACK_UI,
                    memberNames = members.associate { it.uid to it.displayName },
                )
            }
        }

    private fun List<MemberDoc>.roleOf(uid: String): Role = firstOrNull { it.uid == uid }?.role ?: Role.KID

    companion object {
        /** For previews and tests. */
        fun fixed(context: HouseholdContext): Flow<HouseholdContext> = flowOf(context)
    }
}
