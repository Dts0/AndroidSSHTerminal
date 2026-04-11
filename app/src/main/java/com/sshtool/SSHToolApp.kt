package com.sshtool

import android.app.Application
import com.sshtool.data.repository.HostRepository

class SSHToolApp : Application() {
    
    lateinit var hostRepository: HostRepository
        private set
    
    override fun onCreate() {
        super.onCreate()
        instance = this
        hostRepository = HostRepository(this)
    }
    
    companion object {
        lateinit var instance: SSHToolApp
            private set
    }
}
