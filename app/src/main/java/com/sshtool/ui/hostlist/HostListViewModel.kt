package com.sshtool.ui.hostlist

import androidx.lifecycle.*
import com.sshtool.data.model.Host
import com.sshtool.data.repository.HostRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HostListViewModel(private val repository: HostRepository) : ViewModel() {
    
    val hosts: StateFlow<List<Host>> = repository.getAllHosts()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    
    fun deleteHost(host: Host) {
        viewModelScope.launch {
            repository.deleteHost(host)
        }
    }
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
