package com.sshtool.background

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.sshtool.R
import com.sshtool.SSHToolApp
import com.sshtool.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** Protects existing user-initiated SSH sessions, never restarts or restores them. */
class KeepAliveService : Service() {
    private val controller get() = SSHToolApp.instance.keepAliveController
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wakeLockLease: KeepAliveLease? = null
    private var stateJob: Job? = null
    private var refreshJob: Job? = null
    private var stopping = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISABLE) {
            controller.setEnabled(false)
            stopProtection()
            return START_NOT_STICKY
        }
        if (!controller.state.value.shouldRun) {
            stopProtection()
            return START_NOT_STICKY
        }
        try {
            stopping = false
            val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
            acquireWakeLock()
            controller.onServiceStarted()
            if (stateJob == null) {
                stateJob = scope.launch {
                    controller.state.collect { state ->
                        if (state.shouldRun) refreshNotification() else stopProtection()
                    }
                }
                refreshJob = scope.launch {
                    controller.notificationRefreshes.collect {
                        if (controller.state.value.shouldRun) refreshNotification()
                    }
                }
            }
        } catch (failure: Exception) {
            failProtection(failure)
        }
        return START_NOT_STICKY
    }

    private fun acquireWakeLock() {
        if (wakeLockLease == null) {
            val lock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:ssh-keep-alive")
                .apply { setReferenceCounted(false) }
            wakeLockLease = KeepAliveLease(
                acquire = { timeout -> lock.acquire(timeout) },
                release = { runCatching { if (lock.isHeld) lock.release() }; Unit },
                schedule = { delayMillis, task ->
                    val job = scope.launch { delay(delayMillis); task() }
                    val cancel: () -> Unit = { job.cancel() }
                    cancel
                },
                onRenewalFailure = { failure -> failProtection(failure) }
            )
        }
        wakeLockLease?.start()
    }

    private fun failProtection(failure: Exception) {
        Log.w("KeepAliveService", "Unable to maintain SSH keep-alive", failure)
        controller.onServiceFailed()
        stopProtection()
    }

    private fun buildNotification(): Notification {
        val context = ContextCompat.getContextForLanguage(this)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(
            CHANNEL_ID, context.getString(R.string.keep_alive_channel), NotificationManager.IMPORTANCE_LOW
        ).apply { setShowBadge(false) })
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val disable = PendingIntent.getService(
            this, 1, Intent(this, KeepAliveService::class.java).setAction(ACTION_DISABLE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_keep_alive)
            .setContentTitle(context.getString(R.string.settings_keep_alive))
            .setContentText(context.getString(R.string.keep_alive_active, controller.state.value.sessionCount))
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, context.getString(R.string.keep_alive_disable), disable)
            .build()
    }

    private fun refreshNotification() {
        if (stopping) return
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) return
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
        } catch (failure: Exception) {
            failProtection(failure)
        }
    }

    private fun stopProtection() {
        stopping = true
        wakeLockLease?.stop()
        wakeLockLease = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        try {
            controller.onTaskRemoved()
        } catch (failure: Exception) {
            Log.w("KeepAliveService", "Task removal cleanup failed", failure)
        } finally {
            stopProtection()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        scope.cancel()
        stopProtection()
        controller.onServiceStopped()
        super.onDestroy()
    }

    private companion object {
        const val ACTION_DISABLE = "com.sshtool.action.DISABLE_KEEP_ALIVE"
        const val CHANNEL_ID = "ssh_keep_alive"
        const val NOTIFICATION_ID = 1001
    }
}
