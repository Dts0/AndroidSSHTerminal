package com.sshtool.ui.terminal

import androidx.lifecycle.*
import com.sshtool.data.model.Host
import com.sshtool.data.repository.HostRepository
import kotlinx.coroutines.launch

class TerminalViewModel(private val repository: HostRepository) : ViewModel() {
    
    suspend fun getHost(id: Long): Host? = repository.getHostById(id)
}

class TerminalViewModelFactory(
    private val repository: HostRepository
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(TerminalViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return TerminalViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
