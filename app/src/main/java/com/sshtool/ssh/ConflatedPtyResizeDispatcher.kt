package com.sshtool.ssh

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

internal data class PtySizeVersion(
    val columns: Int,
    val rows: Int,
    val version: Long
)

/**
 * Serializes PTY window changes without blocking callers or queuing one task
 * per layout frame. One resize may already be in flight; all later updates are
 * conflated to the newest desired tuple.
 */
internal class ConflatedPtyResizeDispatcher<T : Any>(
    scope: CoroutineScope,
    initialColumns: Int,
    initialRows: Int,
    private val targetProvider: () -> T?,
    private val isTargetConnected: (T) -> Boolean,
    private val sendResize: (T, Int, Int) -> Unit,
    private val onFailure: (Exception) -> Unit
) {

    init {
        require(initialColumns > 0)
        require(initialRows > 0)
    }

    private val lock = Any()
    private val signals = Channel<Unit>(Channel.CONFLATED)
    private var desiredColumns = initialColumns
    private var desiredRows = initialRows
    private var desiredVersion = 0L
    private var appliedVersion = -1L
    private var appliedColumns: Int? = null
    private var appliedRows: Int? = null
    private var ready = false
    private var closed = false
    private val worker: Job = scope.launch { processSignals() }

    fun update(columns: Int, rows: Int) {
        if (columns <= 0 || rows <= 0) return
        val changed = synchronized(lock) {
            if (closed || (desiredColumns == columns && desiredRows == rows)) {
                false
            } else {
                desiredColumns = columns
                desiredRows = rows
                desiredVersion += 1
                true
            }
        }
        if (changed) signals.trySend(Unit)
    }

    /** Snapshot the dimensions to place in JSch's initial pty-req. */
    fun snapshotForInitialRequest(): PtySizeVersion = synchronized(lock) {
        PtySizeVersion(desiredColumns, desiredRows, desiredVersion)
    }

    /**
     * Mark the pre-connect snapshot as applied, then flush any newer resize
     * that arrived while the blocking channel connect was in progress.
     */
    fun markInitialRequestApplied(size: PtySizeVersion) {
        val shouldSignal = synchronized(lock) {
            if (closed) {
                false
            } else {
                appliedVersion = maxOf(appliedVersion, size.version)
                appliedColumns = size.columns
                appliedRows = size.rows
                ready = true
                true
            }
        }
        if (shouldSignal) signals.trySend(Unit)
    }

    fun close() {
        val shouldClose = synchronized(lock) {
            if (closed) {
                false
            } else {
                closed = true
                ready = false
                true
            }
        }
        if (shouldClose) {
            signals.close()
            worker.cancel()
        }
    }

    private suspend fun processSignals() {
        for (ignored in signals) {
            while (true) {
                val pending = synchronized(lock) {
                    if (closed || !ready) return@synchronized null
                    val target = targetProvider() ?: return@synchronized null
                    if (!isTargetConnected(target) || appliedVersion >= desiredVersion) {
                        return@synchronized null
                    }
                    if (appliedColumns == desiredColumns && appliedRows == desiredRows) {
                        // Geometry changed and returned to the tuple that is
                        // already installed before this worker got CPU time.
                        appliedVersion = desiredVersion
                        return@synchronized null
                    }
                    PendingResize(target, desiredColumns, desiredRows, desiredVersion)
                } ?: break

                try {
                    sendResize(pending.target, pending.columns, pending.rows)
                } catch (failure: Exception) {
                    onFailure(failure)
                    // The peer may have received the packet even if the local
                    // write reported an error. Treat window-change as best
                    // effort and wait for a genuinely new geometry instead of
                    // risking duplicate SIGWINCH delivery.
                    break
                }

                synchronized(lock) {
                    if (!closed && targetProvider() === pending.target) {
                        appliedVersion = maxOf(appliedVersion, pending.version)
                        appliedColumns = pending.columns
                        appliedRows = pending.rows
                    }
                }
            }
        }
    }

    private data class PendingResize<T : Any>(
        val target: T,
        val columns: Int,
        val rows: Int,
        val version: Long
    )
}
