package com.sshtool.ui.terminal

/** One cancellable repeat stream. Call from the UI thread; scheduling is injectable for tests. */
internal class KeyRepeatController(
    private val schedule: (delayMillis: Long, task: () -> Unit) -> (() -> Unit)
) {
    private var generation = 0L
    private var cancelPending: (() -> Unit)? = null
    private var onStopped: (() -> Unit)? = null

    fun start(
        canSend: () -> Boolean,
        send: () -> Unit,
        onStopped: () -> Unit
    ) {
        stop()
        val token = generation
        this.onStopped = onStopped
        if (!canSend()) {
            stop()
            return
        }
        send()
        if (generation == token) scheduleNext(token, INITIAL_DELAY_MS, canSend, send)
    }

    fun stop() {
        // A dequeued callback may still run after cancellation. Its token must also be invalidated.
        generation++
        cancelPending?.invoke()
        cancelPending = null
        val cleanup = onStopped
        onStopped = null
        cleanup?.invoke()
    }

    private fun scheduleNext(
        token: Long,
        delayMillis: Long,
        canSend: () -> Boolean,
        send: () -> Unit
    ) {
        cancelPending = schedule(delayMillis) {
            if (generation != token) return@schedule
            cancelPending = null
            if (!canSend()) {
                stop()
                return@schedule
            }
            send()
            if (generation == token) scheduleNext(token, REPEAT_INTERVAL_MS, canSend, send)
        }
    }

    companion object {
        const val INITIAL_DELAY_MS = 400L
        const val REPEAT_INTERVAL_MS = 200L
    }
}
