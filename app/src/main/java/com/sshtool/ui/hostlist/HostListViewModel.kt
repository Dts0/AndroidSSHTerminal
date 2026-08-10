package com.sshtool.ui.hostlist

import androidx.lifecycle.*
import com.sshtool.data.model.Host
import com.sshtool.data.repository.HostRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HostListViewModel(private val repository: HostRepository) : ViewModel() {

    private val _events = Channel<HostListEvent>(Channel.BUFFERED)
    val events: Flow<HostListEvent> = _events.receiveAsFlow()
    
    val hosts: StateFlow<List<Host>> = repository.getAllHosts()
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
            emptyList()
        )
    
    fun deleteHost(host: Host) {
        viewModelScope.launch {
            try {
                repository.deleteHost(host)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A buffered channel retains the one-shot event while the view
                // is temporarily STOPPED (for example during rotation) without
                // replaying an already-consumed error on every recreation.
                _events.send(HostListEvent.DeleteFailed(e.message))
            }
        }
    }
}

sealed interface HostListEvent {
    data class DeleteFailed(val message: String?) : HostListEvent
}

class HostListViewModelFactory(
    private val repository: HostRepository
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(HostListViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return HostListViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
