package com.sshtool.background

/** Routes demand by owner identity; late ViewModel cleanup cannot stop a newer owner. */
internal class KeepAliveSessionOwner(private val updateCount: (Int) -> Unit) {
    private data class Owner(val closeSessions: () -> Unit, var count: Int = 0)
    private val owners = java.util.IdentityHashMap<Any, Owner>()

    fun attach(token: Any, closeSessions: () -> Unit) {
        check(!owners.containsKey(token)) { "SSH session owner already registered" }
        owners[token] = Owner(closeSessions)
    }

    fun detach(token: Any) {
        if (owners.remove(token) != null) publishCount()
    }

    fun update(token: Any, count: Int) {
        require(count >= 0)
        val owner = owners[token] ?: return
        if (owner.count == count) return
        owner.count = count
        publishCount()
    }

    fun onTaskRemoved() {
        val callbacks = owners.values.map { it.closeSessions }
        // Drop callbacks even if Android never invokes the old ViewModels' onCleared.
        owners.clear()
        var failure: Exception? = null
        try {
            callbacks.forEach { cleanup ->
                try {
                    cleanup()
                } catch (error: Exception) {
                    if (failure == null) failure = error else failure?.addSuppressed(error)
                }
            }
        } finally {
            publishCount()
        }
        failure?.let { throw it }
    }

    private fun publishCount() = updateCount(owners.values.sumOf { it.count })
}
