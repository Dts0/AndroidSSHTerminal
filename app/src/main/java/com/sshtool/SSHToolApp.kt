package com.sshtool

import android.app.Application
import com.sshtool.data.repository.HostRepository

class SSHToolApp : Application() {

    /**
     * Lazily constructed so the Keystore + Room initialization
     * (HostRepository builds EncryptedSharedPreferences and the Room DB) does
     * not run on the main thread during Application.onCreate, shaving cold
     * start time. First access pays the cost on whichever thread reads it.
     */
    val hostRepository: HostRepository by lazy { HostRepository(this) }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: SSHToolApp
            private set
    }
}
