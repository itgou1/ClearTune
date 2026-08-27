package com.cleartune.app.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cleartune.core.model.ConnectionResult
import com.cleartune.core.model.ClearTuneError
import com.cleartune.core.model.ServerCredentials
import com.cleartune.core.model.ServerProfile
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface AuthUiState {
    data object Restoring : AuthUiState

    data class Login(
        val address: String = "",
        val username: String = "",
        val allowHttp: Boolean = false,
        val isConnecting: Boolean = false,
        val errorMessage: String? = null,
    ) : AuthUiState

    data class Connected(
        val profile: ServerProfile,
        val restoredOffline: Boolean = false,
    ) : AuthUiState
}

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val repository: AuthRepository,
) : ViewModel() {
    private val _state = MutableStateFlow<AuthUiState>(AuthUiState.Restoring)
    val state: StateFlow<AuthUiState> = _state.asStateFlow()

    init {
        restore()
    }

    fun connect(
        address: String,
        username: String,
        password: String,
        allowHttp: Boolean,
    ) {
        if (address.isBlank() || username.isBlank() || password.isBlank()) {
            _state.value = AuthUiState.Login(
                address = address,
                username = username,
                allowHttp = allowHttp,
                errorMessage = "请填写服务器地址、用户名和密码",
            )
            return
        }
        _state.value = AuthUiState.Login(
            address = address,
            username = username,
            allowHttp = allowHttp,
            isConnecting = true,
        )
        viewModelScope.launch {
            when (val result = repository.connectAndSave(
                ServerCredentials(address, username, password, allowHttp),
            )) {
                is ConnectionResult.Success -> _state.value = AuthUiState.Connected(result.profile)
                is ConnectionResult.Failure -> _state.value = AuthUiState.Login(
                    address = address,
                    username = username,
                    allowHttp = allowHttp,
                    errorMessage = result.error.userMessage,
                )
            }
        }
    }

    fun logout() {
        viewModelScope.launch {
            repository.logout()
            _state.value = AuthUiState.Login()
        }
    }

    private fun restore() {
        viewModelScope.launch {
            val restored = repository.restore()
            val credentials = restored.credentials
            _state.value = when (val result = restored.connectionResult) {
                is ConnectionResult.Success -> AuthUiState.Connected(result.profile)
                is ConnectionResult.Failure -> {
                    val canUseOfflineSession = result.error.allowsOfflineRestore()
                    if (canUseOfflineSession && restored.cachedProfile != null) {
                        AuthUiState.Connected(
                            profile = restored.cachedProfile,
                            restoredOffline = true,
                        )
                    } else {
                        AuthUiState.Login(
                            address = credentials?.baseUrl.orEmpty(),
                            username = credentials?.username.orEmpty(),
                            allowHttp = credentials?.allowInsecureHttp ?: false,
                            errorMessage = result.error.userMessage,
                        )
                    }
                }
                null -> AuthUiState.Login()
            }
        }
    }
}

internal fun ClearTuneError.allowsOfflineRestore(): Boolean =
    this is ClearTuneError.Timeout || this is ClearTuneError.Unreachable
