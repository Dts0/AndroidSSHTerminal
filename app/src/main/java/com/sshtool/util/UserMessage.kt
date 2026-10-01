package com.sshtool.util

import androidx.annotation.StringRes
import com.sshtool.R
import java.io.IOException

/** Locale-independent text retained in ViewModels, connection state, and exceptions. */
data class UserMessage(@StringRes val resourceId: Int, val args: List<Any> = emptyList()) {
    constructor(@StringRes resourceId: Int, vararg args: Any) : this(resourceId, args.toList())

    /** Resolve only at the UI boundary, using the new Activity's resources after recreation. */
    fun resolve(format: (Int, Array<out Any>) -> String): String = format(
        resourceId,
        args.map { if (it is UserMessage) it.resolve(format) else it }.toTypedArray()
    )

    companion object {
        fun fromThrowable(failure: Throwable, @StringRes fallbackResourceId: Int): UserMessage {
            // Wrapped app errors keep their structured text. Never translate by comparing
            // exception strings; third-party technical details remain verbatim.
            val seen = java.util.Collections.newSetFromMap(
                java.util.IdentityHashMap<Throwable, Boolean>()
            )
            var current: Throwable? = failure
            while (current != null && seen.add(current)) {
                if (current is UserMessageCarrier) return current.userMessage
                current = current.cause
            }
            val fallback = UserMessage(fallbackResourceId)
            val detail = failure.localizedMessage?.takeIf { it.isNotBlank() }
            return if (detail == null) fallback
            else UserMessage(R.string.error_with_detail, fallback, detail)
        }
    }
}

interface UserMessageCarrier {
    val userMessage: UserMessage
}

// Preserve exception categories relied on by repository rollback and SSH tests.
class UserIOException(
    override val userMessage: UserMessage, cause: Throwable? = null
) : IOException("App error (${userMessage.resourceId})", cause), UserMessageCarrier

class UserStateException(
    override val userMessage: UserMessage, cause: Throwable? = null
) : IllegalStateException("App error (${userMessage.resourceId})", cause), UserMessageCarrier

class UserArgumentException(
    override val userMessage: UserMessage
) : IllegalArgumentException("App error (${userMessage.resourceId})"), UserMessageCarrier
