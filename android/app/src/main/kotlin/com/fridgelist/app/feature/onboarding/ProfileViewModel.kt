package com.fridgelist.app.feature.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fridgelist.app.ui.common.LanguageOptions
import com.fridgelist.core.data.auth.AuthRepository
import com.fridgelist.core.data.session.OnboardingStep
import com.fridgelist.core.data.session.SessionStore
import com.fridgelist.core.data.user.UserRepository
import com.fridgelist.core.model.Limits
import com.fridgelist.core.model.clip
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ProfileUiState(
    val name: String = "",
    val itemLanguage: String = "",
    val nameInvalid: Boolean = false,
    val next: OnboardingStep? = null,
)

/** Display name (1–40) and item language (CONTRACT §3.1/§3.4). Works offline. */
@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val auth: AuthRepository,
    private val users: UserRepository,
    private val session: SessionStore,
    private val steps: StepResolver,
    private val uiLanguage: UiLanguageProvider,
) : ViewModel() {

    private val _state = MutableStateFlow(ProfileUiState(itemLanguage = uiLanguage.current()))
    val state: StateFlow<ProfileUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val saved = session.current()
            val name = saved.profileName ?: auth.currentUser()?.displayName.orEmpty()
            _state.update { it.copy(name = name, itemLanguage = saved.itemLanguage ?: it.itemLanguage) }
        }
    }

    fun onNameChange(value: String) = _state.update { it.copy(name = value.clip(Limits.DISPLAY_NAME_MAX + 10), nameInvalid = false) }

    fun onLanguageChange(tag: String) = _state.update { it.copy(itemLanguage = tag) }

    private var saving = false

    fun save() {
        if (saving) return
        val name = _state.value.name.trim()
        if (!isValidName(name, Limits.DISPLAY_NAME_MAX)) {
            _state.update { it.copy(nameInvalid = true) }
            return
        }
        val language = _state.value.itemLanguage
        saving = true
        viewModelScope.launch {
            try {
                session.saveProfile(name, language)
                auth.currentUser()?.let { users.saveProfile(it.uid, name, language, uiLanguage.current()) }
                _state.update { it.copy(next = steps.next()) }
            } finally {
                saving = false
            }
        }
    }

    fun onNavigated() = _state.update { it.copy(next = null) }
}

fun isValidName(name: String, max: Int): Boolean = name.trim().length in 1..max

/** Seam for tests: the current UI language as a contract tag. */
fun interface UiLanguageProvider {
    fun current(): String
}

class AppUiLanguageProvider @Inject constructor() : UiLanguageProvider {
    override fun current(): String = LanguageOptions.currentUiLanguage()
}
