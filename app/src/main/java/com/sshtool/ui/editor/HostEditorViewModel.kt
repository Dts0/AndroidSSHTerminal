package com.sshtool.ui.editor

import androidx.lifecycle.*
import com.sshtool.data.model.Host
import com.sshtool.data.repository.HostRepository
import kotlinx.coroutines.launch

class HostEditorViewModel(private val repository: HostRepository) : ViewModel() {
    
    suspend fun getHost(id: Long): Host? = repository.getHostById(id)
    
    suspend fun saveHost(host: Host) {
        repository.saveHost(host)
    }
    
    suspend fun updateHost(host: Host) {
        repository.updateHost(host)
    }
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
