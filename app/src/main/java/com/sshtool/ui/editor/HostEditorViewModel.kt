package com.sshtool.ui.editor

import androidx.lifecycle.*
import com.sshtool.data.model.Host
import com.sshtool.data.repository.HostRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

data class HostEditorDraft(
    val name: String = "",
    val host: String = "",
    val port: String = "22",
    val username: String = "",
    val useKeyAuth: Boolean = false,
    // These secrets are deliberately held only in this ordinary ViewModel's
    // memory. They are never put in SavedStateHandle or Android view state.
    val password: String = "",
    val privateKey: String = "",
    val passphrase: String = ""
)

data class HostEditorUiState(
    val draft: HostEditorDraft = HostEditorDraft(),
    val isLoading: Boolean = false,
    val loadCompleted: Boolean = false,
    val isSaving: Boolean = false,
    val saveCompleted: Boolean = false,
    val editingExisting: Boolean = false,
    val canKeepPassword: Boolean = false,
    val canKeepPrivateKey: Boolean = false
)

class HostEditorViewModel(private val repository: HostRepository) : ViewModel() {

    private enum class DraftField {
        NAME, HOST, PORT, USERNAME, PASSWORD, PRIVATE_KEY, PASSPHRASE
    }

    private val _uiState = MutableStateFlow(HostEditorUiState())
    val uiState: StateFlow<HostEditorUiState> = _uiState.asStateFlow()

    private val _events = Channel<HostEditorEvent>(Channel.BUFFERED)
    val events: Flow<HostEditorEvent> = _events.receiveAsFlow()

    private val dirtyFields = mutableSetOf<DraftField>()
    private var authTypeDirty = false
    private var requestedHostId: Long? = null
    private var existingHost: Host? = null

    fun initialize(hostId: Long) {
        val priorRequest = requestedHostId
        if (priorRequest != null) {
            check(priorRequest == hostId) { "HostEditorViewModel reused for a different host" }
            return
        }
        requestedHostId = hostId
        if (hostId == -1L) {
            _uiState.value = _uiState.value.copy(loadCompleted = true)
            return
        }

        _uiState.value = _uiState.value.copy(isLoading = true)
        viewModelScope.launch {
            try {
                val loaded = repository.getHostById(hostId)
                if (loaded == null) {
                    _uiState.value = _uiState.value.copy(isLoading = false)
                    _events.send(HostEditorEvent.HostNotFound)
                    return@launch
                }
                existingHost = loaded
                val current = _uiState.value.draft
                val merged = current.copy(
                    name = loaded.name.takeUnless { DraftField.NAME in dirtyFields } ?: current.name,
                    host = loaded.host.takeUnless { DraftField.HOST in dirtyFields } ?: current.host,
                    port = loaded.port.toString()
                        .takeUnless { DraftField.PORT in dirtyFields } ?: current.port,
                    username = loaded.username
                        .takeUnless { DraftField.USERNAME in dirtyFields } ?: current.username,
                    useKeyAuth = loaded.useKeyAuth.takeUnless { authTypeDirty }
                        ?: current.useKeyAuth
                    // Existing secrets intentionally remain blank in the form;
                    // submit() keeps them from the private existingHost snapshot.
                )
                _uiState.value = _uiState.value.copy(
                    draft = merged,
                    isLoading = false,
                    loadCompleted = true,
                    editingExisting = true,
                    canKeepPassword = !loaded.useKeyAuth && loaded.password != null,
                    canKeepPrivateKey = loaded.useKeyAuth && loaded.privateKey != null
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoading = false)
                _events.send(HostEditorEvent.LoadFailed(e.message))
            }
        }
    }

    fun updateName(value: String) = updateField(DraftField.NAME) { copy(name = value) }
    fun updateHost(value: String) = updateField(DraftField.HOST) { copy(host = value) }
    fun updatePort(value: String) = updateField(DraftField.PORT) { copy(port = value) }
    fun updateUsername(value: String) = updateField(DraftField.USERNAME) { copy(username = value) }
    fun updatePassword(value: String) = updateField(DraftField.PASSWORD) { copy(password = value) }
    fun updatePrivateKey(value: String) =
        updateField(DraftField.PRIVATE_KEY) { copy(privateKey = value) }
    fun updatePassphrase(value: String) =
        updateField(DraftField.PASSPHRASE) { copy(passphrase = value) }

    fun updateAuthType(useKeyAuth: Boolean) {
        authTypeDirty = true
        val state = _uiState.value
        _uiState.value = state.copy(draft = state.draft.copy(useKeyAuth = useKeyAuth))
    }

    private fun updateField(
        field: DraftField,
        transform: HostEditorDraft.() -> HostEditorDraft
    ) {
        dirtyFields += field
        val state = _uiState.value
        _uiState.value = state.copy(draft = state.draft.transform())
    }
    
    /**
     * Keep an in-flight mutation in the ViewModel so destroying/recreating the
     * Fragment view cannot cancel a partially completed cross-store save or
     * leave the replacement view permanently locked in a local "saving" state.
     */
    fun submit() {
        val state = _uiState.value
        if (state.isSaving || state.saveCompleted || state.isLoading || !state.loadCompleted) return
        val draft = state.draft
        val original = existingHost
        val replacementPrivateKey = draft.privateKey.takeIf(String::isNotBlank)
        val replacementPassphrase = draft.passphrase.takeIf(String::isNotBlank)
        val host = Host(
            id = original?.id ?: 0,
            name = draft.name.trim(),
            host = draft.host.trim(),
            port = draft.port.toIntOrNull() ?: 22,
            username = draft.username.trim(),
            useKeyAuth = draft.useKeyAuth,
            lastConnected = original?.lastConnected,
            createdAt = original?.createdAt ?: System.currentTimeMillis()
        ).apply {
            password = if (!draft.useKeyAuth) {
                draft.password.takeIf(String::isNotBlank)
                    ?: original?.takeIf { !it.useKeyAuth }?.password
            } else {
                null
            }
            privateKey = if (draft.useKeyAuth) {
                replacementPrivateKey
                    ?: original?.takeIf { it.useKeyAuth }?.privateKey
            } else {
                null
            }
            passphrase = if (draft.useKeyAuth) {
                when {
                    replacementPassphrase != null -> replacementPassphrase
                    DraftField.PASSPHRASE in dirtyFields -> null
                    // A passphrase belongs to one private key. Replacing the
                    // key while leaving this field blank must not silently
                    // attach the old key's passphrase to the new key.
                    replacementPrivateKey != null -> null
                    else -> original?.takeIf { it.useKeyAuth }?.passphrase
                }
            } else {
                null
            }
        }

        _uiState.value = state.copy(isSaving = true)
        viewModelScope.launch {
            var event: HostEditorEvent? = null
            try {
                if (original != null) {
                    repository.updateHost(host)
                } else {
                    repository.saveHost(host)
                }
                _uiState.value = _uiState.value.copy(saveCompleted = true)
                event = HostEditorEvent.SaveSucceeded
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                event = HostEditorEvent.SaveFailed(e.message)
            } finally {
                _uiState.value = _uiState.value.copy(isSaving = false)
            }
            event?.let { _events.send(it) }
        }
    }
}

sealed interface HostEditorEvent {
    data object HostNotFound : HostEditorEvent
    data class LoadFailed(val message: String?) : HostEditorEvent
    data object SaveSucceeded : HostEditorEvent
    data class SaveFailed(val message: String?) : HostEditorEvent
}

class HostEditorViewModelFactory(
    private val repository: HostRepository
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(HostEditorViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return HostEditorViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
