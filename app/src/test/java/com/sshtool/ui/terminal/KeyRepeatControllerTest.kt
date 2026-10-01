package com.sshtool.ui.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

class KeyRepeatControllerTest {
    private class FakeScheduler {
        data class Task(val due: Long, val action: () -> Unit, var cancelled: Boolean = false)

        var now = 0L
            private set
        val tasks = mutableListOf<Task>()
        val pendingCount get() = tasks.count { !it.cancelled }

        fun schedule(delay: Long, action: () -> Unit): () -> Unit {
            val task = Task(now + delay, action)
            tasks += task
            return { task.cancelled = true }
        }

        fun advanceTo(target: Long) {
            require(target >= now)
            while (true) {
                val task = tasks.filter { !it.cancelled && it.due <= target }
                    .minByOrNull { it.due } ?: break
                tasks.remove(task)
                now = task.due
                task.action()
            }
            now = target
        }
    }

    @Test
    fun `press sends immediately then repeats at 400 ms and every 200 ms`() {
        val scheduler = FakeScheduler()
        val controller = KeyRepeatController(scheduler::schedule)
        val sent = mutableListOf<Long>()
        controller.start({ true }, { sent += scheduler.now }, {})
        assertEquals(listOf(0L), sent)
        scheduler.advanceTo(399)
        assertEquals(listOf(0L), sent)
        scheduler.advanceTo(400)
        assertEquals(listOf(0L, 400L), sent)
        scheduler.advanceTo(599)
        assertEquals(listOf(0L, 400L), sent)
        scheduler.advanceTo(1_000)
        assertEquals(listOf(0L, 400L, 600L, 800L, 1_000L), sent)
        assertEquals(1, scheduler.pendingCount)
    }

    @Test
    fun `short tap sends exactly once and cancels first repeat`() {
        val scheduler = FakeScheduler()
        val controller = KeyRepeatController(scheduler::schedule)
        var sent = 0
        var stopped = 0
        controller.start({ true }, { sent++ }, { stopped++ })
        scheduler.advanceTo(100)
        controller.stop()
        controller.stop()
        scheduler.advanceTo(2_000)
        assertEquals(1, sent)
        assertEquals(1, stopped)
        assertEquals(0, scheduler.pendingCount)
    }

    @Test
    fun `release after repeats leaves no scheduled sends`() {
        val scheduler = FakeScheduler()
        val controller = KeyRepeatController(scheduler::schedule)
        var sent = 0
        controller.start({ true }, { sent++ }, {})
        scheduler.advanceTo(800)
        controller.stop()
        scheduler.advanceTo(2_000)
        assertEquals(4, sent)
        assertEquals(0, scheduler.pendingCount)
    }

    @Test
    fun `input disabled before first repeat stops and clears pressed state`() {
        val scheduler = FakeScheduler()
        val controller = KeyRepeatController(scheduler::schedule)
        var enabled = true
        var sent = 0
        var stopped = 0
        controller.start({ enabled }, { sent++ }, { stopped++ })
        enabled = false
        scheduler.advanceTo(2_000)
        assertEquals(1, sent)
        assertEquals(1, stopped)
        assertEquals(0, scheduler.pendingCount)
    }

    @Test
    fun `invalid initial state sends nothing and schedules nothing`() {
        val scheduler = FakeScheduler()
        val controller = KeyRepeatController(scheduler::schedule)
        var sent = 0
        var stopped = 0
        controller.start({ false }, { sent++ }, { stopped++ })
        scheduler.advanceTo(2_000)
        assertEquals(0, sent)
        assertEquals(1, stopped)
        assertEquals(0, scheduler.pendingCount)
    }

    @Test
    fun `session switch never sends held key to the new session or resumes on switch back`() {
        val scheduler = FakeScheduler()
        val controller = KeyRepeatController(scheduler::schedule)
        var activeSession = "first"
        val heldSession = activeSession
        val sent = mutableListOf<String>()
        controller.start({ activeSession == heldSession }, { sent += activeSession }, {})
        scheduler.advanceTo(400)
        activeSession = "second"
        scheduler.advanceTo(600)
        activeSession = "first"
        scheduler.advanceTo(2_000)
        assertEquals(listOf("first", "first"), sent)
        assertEquals(0, scheduler.pendingCount)
    }

    @Test
    fun `new press cancels old stream and restarts long press delay`() {
        val scheduler = FakeScheduler()
        val controller = KeyRepeatController(scheduler::schedule)
        val sent = mutableListOf<String>()
        var stopped = 0
        controller.start({ true }, { sent += "up" }, { stopped++ })
        scheduler.advanceTo(300)
        controller.start({ true }, { sent += "down" }, { stopped++ })
        scheduler.advanceTo(699)
        assertEquals(listOf("up", "down"), sent)
        scheduler.advanceTo(900)
        assertEquals(listOf("up", "down", "down", "down"), sent)
        assertEquals(1, stopped)
        assertEquals(1, scheduler.pendingCount)
    }

    @Test
    fun `already dequeued stale callback cannot send or interfere with new press`() {
        val scheduler = FakeScheduler()
        val controller = KeyRepeatController(scheduler::schedule)
        val sent = mutableListOf<String>()
        controller.start({ true }, { sent += "up" }, {})
        val staleCallback = scheduler.tasks.single().action
        controller.stop()
        controller.start({ true }, { sent += "right" }, {})
        staleCallback()
        scheduler.advanceTo(400)
        assertEquals(listOf("up", "right", "right"), sent)
        assertEquals(1, scheduler.pendingCount)
    }

    @Test
    fun `cancellation from initial send does not schedule another callback`() {
        val scheduler = FakeScheduler()
        val controller = KeyRepeatController(scheduler::schedule)
        var sent = 0
        controller.start({ true }, { sent++; controller.stop() }, {})
        scheduler.advanceTo(2_000)
        assertEquals(1, sent)
        assertEquals(0, scheduler.pendingCount)
    }

    @Test
    fun `cancellation from repeat send does not restart the stream`() {
        val scheduler = FakeScheduler()
        val controller = KeyRepeatController(scheduler::schedule)
        var sent = 0
        controller.start({ true }, {
            sent++
            if (sent == 2) controller.stop()
        }, {})
        scheduler.advanceTo(2_000)
        assertEquals(2, sent)
        assertEquals(0, scheduler.pendingCount)
    }
}
