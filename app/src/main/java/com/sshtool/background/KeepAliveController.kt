package com.sshtool.background

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Application-backed preferences and service demand; never owns an Activity or Fragment. */
internal class KeepAliveController(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences("background_settings", Context.MODE_PRIVATE)
    private val policy = KeepAlivePolicy(
        initialEnabled = preferences.getBoolean("keep_alive", false),
        persistEnabled = { preferences.edit().putBoolean("keep_alive", it).apply() },
        startService = {
            ContextCompat.startForegroundService(appContext, Intent(appContext, KeepAliveService::class.java))
            Unit
        },
        stopService = { appContext.stopService(Intent(appContext, KeepAliveService::class.java)); Unit }
    )
    val state = policy.state
    private val _notificationRefreshes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val notificationRefreshes = _notificationRefreshes.asSharedFlow()
    private val sessionOwner = KeepAliveSessionOwner(policy::updateSessionCount)

    fun attachOwner(token: Any, closeSessions: () -> Unit) = sessionOwner.attach(token, closeSessions)
    fun detachOwner(token: Any) = sessionOwner.detach(token)
    fun updateSessionCount(token: Any, count: Int) = sessionOwner.update(token, count)

    fun setEnabled(enabled: Boolean) = policy.setEnabled(enabled)

    fun setForeground(foreground: Boolean) {
        policy.setForeground(foreground)
        // AppCompat language changes may recreate only the Activity on API 26–32.
        if (foreground) _notificationRefreshes.tryEmit(Unit)
    }

    fun onTaskRemoved() {
        policy.setForeground(false)
        sessionOwner.onTaskRemoved()
    }

    fun onServiceStarted() = policy.onServiceStarted()
    fun onServiceStopped() = policy.onServiceStopped()
    fun onServiceFailed() = policy.onServiceFailed()
}
