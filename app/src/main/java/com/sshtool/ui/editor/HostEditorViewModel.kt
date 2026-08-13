package com.sshtool.ui.editor

import androidx.lifecycle.*
import com.sshtool.data.model.Host
import com.sshtool.data.model.PortForwardDirection
import com.sshtool.data.model.PortForwardRule
import com.sshtool.data.model.portForwardBindAddressesConflict as sharedBindAddressesConflict
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
    val passphrase: String = "",
    val portForwardRules: List<PortForwardRule> = emptyList()
)

data class HostEditorUiState(
    val draft: HostEditorDraft = HostEditorDraft(),
    val isLoading: Boolean = false,
    val loadCompleted: Boolean = false,
    val isSaving: Boolean = false,
    val saveCompleted: Boolean = false,
    val editingExisting: Boolean = false,
    val canKeepPassword: Boolean = false,
    val canKeepPrivateKey: Boolean = false,
    val advancedSettingsExpanded: Boolean = false
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
    private var portForwardRulesDirty = false
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
                val loadedProfile = repository.getEditableHostProfile(hostId)
                if (loadedProfile == null) {
                    _uiState.value = _uiState.value.copy(isLoading = false)
                    _events.send(HostEditorEvent.HostNotFound)
                    return@launch
                }
                val loaded = loadedProfile.host
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
                        ?: current.useKeyAuth,
                    portForwardRules = loadedProfile.portForwardRules
                        .takeUnless { portForwardRulesDirty }
                        ?.withCanonicalDraftOrder() ?: current.portForwardRules
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

    fun toggleAdvancedSettings() {
        val state = _uiState.value
        if (state.isSaving || state.saveCompleted) return
        _uiState.value = state.copy(
            advancedSettingsExpanded = !state.advancedSettingsExpanded
        )
    }

    fun addPortForwardRule(rule: PortForwardRule) {
        val state = _uiState.value
        if (!state.canEditPortForwardDraft()) return
        portForwardRulesDirty = true
        val rules = state.draft.portForwardRules + rule.copy(
            id = 0,
            hostId = existingHost?.id ?: 0,
            sortOrder = state.draft.portForwardRules.size
        )
        _uiState.value = state.copy(draft = state.draft.copy(portForwardRules = rules))
    }

    fun updatePortForwardRule(index: Int, rule: PortForwardRule) {
        val state = _uiState.value
        if (!state.canEditPortForwardDraft() ||
            index !in state.draft.portForwardRules.indices
        ) {
            return
        }
        val previous = state.draft.portForwardRules[index]
        val replacement = rule.copy(
            id = previous.id,
            hostId = previous.hostId,
            sortOrder = index
        )
        if (replacement == previous) return
        portForwardRulesDirty = true
        val rules = state.draft.portForwardRules.toMutableList().apply {
            set(
                index,
                replacement
            )
        }.withCanonicalDraftOrder()
        _uiState.value = state.copy(draft = state.draft.copy(portForwardRules = rules))
    }

    fun removePortForwardRule(index: Int) {
        val state = _uiState.value
        if (!state.canEditPortForwardDraft() ||
            index !in state.draft.portForwardRules.indices
        ) {
            return
        }
        portForwardRulesDirty = true
        val rules = state.draft.portForwardRules
            .filterIndexed { ruleIndex, _ -> ruleIndex != index }
            .mapIndexed { ruleIndex, rule -> rule.copy(sortOrder = ruleIndex) }
        _uiState.value = state.copy(draft = state.draft.copy(portForwardRules = rules))
    }

    fun setPortForwardRuleEnabled(index: Int, enabled: Boolean) {
        val state = _uiState.value
        if (!state.canEditPortForwardDraft() ||
            index !in state.draft.portForwardRules.indices
        ) {
            return
        }
        val current = state.draft.portForwardRules[index]
        if (current.enabled == enabled) return
        portForwardRulesDirty = true
        val rules = state.draft.portForwardRules.toMutableList().apply {
            set(index, current.copy(enabled = enabled))
        }
        _uiState.value = state.copy(draft = state.draft.copy(portForwardRules = rules))
    }

    internal fun hasDuplicatePortForward(
        direction: PortForwardDirection,
        bindAddress: String,
        listenPort: Int,
        excludingIndex: Int? = null
    ): Boolean = _uiState.value.draft.portForwardRules.withIndex().any { (index, rule) ->
        index != excludingIndex &&
            rule.direction == direction &&
            rule.listenPort == listenPort &&
            portForwardBindAddressesConflict(direction, rule.bindAddress, bindAddress)
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
        val replacePortForwardRules = portForwardRulesDirty
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
                val normalizedRules = draft.portForwardRules.mapIndexed { index, rule ->
                    rule.copy(
                        // Preserve an existing rule's identity across the
                        // editor/repository hand-off. New drafts keep id=0.
                        hostId = original?.id ?: 0,
                        bindAddress = rule.bindAddress.trim(),
                        targetHost = rule.targetHost.trim(),
                        sortOrder = index
                    )
                }
                val savedHostId = if (original != null) {
                    if (replacePortForwardRules) {
                        repository.updateHost(host, normalizedRules)
                    } else {
                        // Avoid deleting/reinserting unchanged child rows when
                        // the user only edited ordinary host fields. This also
                        // keeps their persisted rule IDs stable.
                        repository.updateHost(host)
                    }
                    original.id
                } else {
                    repository.saveHost(host, normalizedRules)
                }
                _uiState.value = _uiState.value.copy(saveCompleted = true)
                event = HostEditorEvent.SaveSucceeded(
                    hostId = savedHostId,
                    editingExisting = original != null
                )
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

/**
 * JSch/Java accept bracketed IPv6 literals, while users commonly enter the
 * same address without brackets. Treat those spellings (and DNS's optional
 * trailing dot) as the same listening endpoint for duplicate detection.
 */
internal fun String.portForwardAddressComparisonKey(): String {
    var normalized = trim().lowercase()
    if (normalized.length >= 2 && normalized.first() == '[' && normalized.last() == ']') {
        normalized = normalized.substring(1, normalized.lastIndex)
    }
    if (':' !in normalized) normalized = normalized.removeSuffix(".")
    return normalized
}

/** Purely syntactic so editing an address never performs a DNS lookup. */
internal fun String.isLoopbackPortForwardAddress(): Boolean {
    val normalized = portForwardAddressComparisonKey()
    if (normalized == "localhost" || normalized.endsWith(".localhost")) return true

    fun isIpv4Loopback(value: String): Boolean {
        val octets = value.split('.')
        if (octets.size != 4) return false
        val numbers = octets.map { it.toIntOrNull() ?: return false }
        return numbers.all { it in 0..255 } && numbers.first() == 127
    }

    if (isIpv4Loopback(normalized)) return true
    val ipv6 = normalized.substringBefore('%')
    if (ipv6 == "::1") return true
    if (ipv6.startsWith("::ffff:") && isIpv4Loopback(ipv6.removePrefix("::ffff:"))) {
        return true
    }
    val hextets = ipv6.split(':')
    return hextets.size == 8 && hextets.all { hextet ->
        hextet.length in 1..4 && hextet.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
    } && hextets.dropLast(1).all { it.toInt(16) == 0 } && hextets.last().toInt(16) == 1
}

/** Match the distinct bind-address semantics used by JSch 0.2.18. */
internal fun portForwardBindAddressesConflict(
    direction: PortForwardDirection,
    left: String,
    right: String
): Boolean = sharedBindAddressesConflict(direction, left, right)

private fun List<PortForwardRule>.withCanonicalDraftOrder(): List<PortForwardRule> =
    mapIndexed { index, rule ->
        if (rule.sortOrder == index) rule else rule.copy(sortOrder = index)
    }

private fun HostEditorUiState.canEditPortForwardDraft(): Boolean =
    loadCompleted && !isLoading && !isSaving && !saveCompleted

sealed interface HostEditorEvent {
    data object HostNotFound : HostEditorEvent
    data class LoadFailed(val message: String?) : HostEditorEvent
    data class SaveSucceeded(
        val hostId: Long,
        val editingExisting: Boolean
    ) : HostEditorEvent
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
