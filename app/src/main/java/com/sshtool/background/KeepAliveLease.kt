package com.sshtool.background

/** Bounded, renewable wake-lock lease. All stop/failure paths are idempotent. */
internal class KeepAliveLease(
    private val acquire: (Long) -> Unit,
    private val release: () -> Unit,
    private val schedule: (Long, () -> Unit) -> (() -> Unit),
    private val onRenewalFailure: (Exception) -> Unit
) {
    private var active = false
    private var generation = 0L
    private var cancelPending: (() -> Unit)? = null

    fun start() {
        if (active) return
        active = true
        val token = ++generation
        try {
            acquire(TIMEOUT_MS)
            scheduleNext(token)
        } catch (failure: Exception) {
            stop()
            throw failure
        }
    }

    fun stop() {
        generation++
        cancelPending?.invoke()
        cancelPending = null
        if (active) {
            active = false
            release()
        }
    }

    private fun scheduleNext(token: Long) {
        cancelPending = schedule(RENEWAL_MS) {
            if (!active || token != generation) return@schedule
            cancelPending = null
            try {
                acquire(TIMEOUT_MS)
                scheduleNext(token)
            } catch (failure: Exception) {
                stop()
                onRenewalFailure(failure)
            }
        }
    }

    companion object {
        const val TIMEOUT_MS = 10 * 60 * 1_000L
        const val RENEWAL_MS = 5 * 60 * 1_000L
    }
}
