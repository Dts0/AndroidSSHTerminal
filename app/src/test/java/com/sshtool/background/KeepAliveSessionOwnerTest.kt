package com.sshtool.background

import org.junit.Assert.*
import org.junit.Test

class KeepAliveSessionOwnerTest {
    @Test fun taskRemovalClosesSessionsOnceAndOldOwnerCannotStopNewOwner() {
        var count = 0
        var closes = 0
        val owner = KeepAliveSessionOwner { count = it }
        val first = Any()
        owner.attach(first) { closes++ }
        owner.update(first, 2)
        assertEquals(2, count)
        owner.onTaskRemoved()
        owner.onTaskRemoved()
        assertEquals(1, closes)
        assertEquals(0, count)

        val second = Any()
        owner.attach(second) { closes++ }
        owner.update(second, 1)
        owner.detach(first)
        owner.update(first, 7)
        assertEquals(1, count)
        owner.detach(second)
        assertEquals(0, count)
        owner.onTaskRemoved()
        assertEquals(1, closes)
    }

    @Test fun independentActivityOwnersShareDemandAndCleanupContinuesAfterFailure() {
        var count = 0
        var closes = 0
        val owner = KeepAliveSessionOwner { count = it }
        val first = Any()
        val second = Any()
        owner.attach(first) { throw IllegalStateException("cleanup failure") }
        owner.attach(second) { closes++ }
        owner.update(first, 2)
        owner.update(second, 1)
        assertEquals(3, count)
        assertThrows(IllegalStateException::class.java) { owner.onTaskRemoved() }
        assertEquals(1, closes)
        assertEquals(0, count)
    }

    @Test fun failedTaskCleanupStillClearsDemandAndAllowsANewOwner() {
        var count = 0
        val owner = KeepAliveSessionOwner { count = it }
        val first = Any()
        owner.attach(first) { throw IllegalStateException("cleanup failure") }
        owner.update(first, 1)
        assertThrows(IllegalStateException::class.java) { owner.onTaskRemoved() }
        assertEquals(0, count)
        owner.attach(Any()) {}
    }
}
