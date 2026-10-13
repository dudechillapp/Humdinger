package com.kowalski.humdinger

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kowalski.humdinger.data.AccountDeletion
import com.kowalski.humdinger.data.UserProfile
import com.kowalski.humdinger.data.UserRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transform

sealed interface SessionState {
    data object Loading : SessionState
    data class Failed(val message: String) : SessionState
    data class Ready(val me: UserProfile) : SessionState
}

/**
 * No sign-up screen: if nobody is signed in we quietly create a guest account,
 * and if the account has no profile yet we give it a fun random name.
 */
class SessionViewModel : ViewModel() {
    private val retries = MutableStateFlow(0)

    /** True right after a brand-new guest gets their fun name, so we can say hello. */
    val justJoined = MutableStateFlow(false)

    fun retry() {
        retries.value++
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<SessionState> = retries
        .flatMapLatest {
            // Everything restarts from here when the user taps "Try again".
            UserRepository.authUid()
                .flatMapLatest { uid ->
                    if (uid == null) {
                        flow<SessionState> {
                            emit(SessionState.Loading)
                            UserRepository.signInAsGuest() // authUid() then emits the new id
                        }
                    } else {
                        UserRepository.profile(uid).transform<UserProfile?, SessionState> { profile ->
                            if (profile != null) {
                                emit(SessionState.Ready(profile))
                            } else if (AccountDeletion.inProgress.value) {
                                // Their account is being deleted: don't give it a new name.
                                emit(SessionState.Loading)
                            } else {
                                emit(SessionState.Loading)
                                UserRepository.createProfileWithFunName(uid) // profile() then emits it
                                justJoined.value = true
                            }
                        }
                    }
                }
                .catch { e ->
                    Log.e("Humdinger", "Session failed", e)
                    emit(SessionState.Failed("Couldn't connect. Check your internet and try again."))
                }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, SessionState.Loading)
}
