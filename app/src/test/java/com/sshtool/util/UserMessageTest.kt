package com.sshtool.util

import com.sshtool.R
import com.sshtool.ssh.SSHConnectionState
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.Locale

class UserMessageTest {
    @Test fun messageIsResolvedAgainInTheCurrentLanguage() {
        val message = UserMessage(R.string.host_not_found)
        assertEquals("Host not found", message.resolve { _, _ -> "Host not found" })
        assertEquals("主机不存在", message.resolve { _, _ -> "主机不存在" })
        assertEquals("主機不存在", message.resolve { _, _ -> "主機不存在" })
        assertEquals("ホストが見つかりません", message.resolve { _, _ -> "ホストが見つかりません" })
    }

    @Test fun connectionStatesRetainUnresolvedMessagesAndKeepLegacyDetails() {
        val message = UserMessage(R.string.error_load_host)
        val state = SSHConnectionState.Error(message)
        assertSame(message, state.copy().userMessage)
        val legacy = SSHConnectionState.Error("remote technical detail")
        assertNull(legacy.userMessage)
        assertEquals("remote technical detail", legacy.message)
    }

    @Test fun formattedArgumentsAreNotTranslated() {
        val message = UserMessage(R.string.status_connecting_host, "my-server-日本")
        assertEquals("Connecting to my-server-日本…", message.resolve { _, args ->
            String.format(Locale.ENGLISH, "Connecting to %1\$s…", *args)
        })
    }

    @Test fun wrappedAppErrorsKeepTheirStructuredMessage() {
        val message = UserMessage(R.string.error_credential_store)
        val failure = IOException("wrapper", UserStateException(message))
        assertSame(message, UserMessage.fromThrowable(failure, R.string.error_unknown))
    }

    @Test fun exceptionCategoriesAndCausesArePreserved() {
        val message = UserMessage(R.string.error_credential_write)
        val cause = IOException("synthetic")
        val failure: IOException = UserIOException(message, cause)
        assertSame(cause, failure.cause)
        assertSame(message, (failure as UserMessageCarrier).userMessage)
        assertTrue(IllegalStateException::class.java.isInstance(UserStateException(message)))
        assertTrue(IllegalArgumentException::class.java.isInstance(UserArgumentException(message)))
    }

    @Test fun thirdPartyDetailsAreVerbatimAndOnlyTheOuterTextIsLocalized() {
        val message = UserMessage.fromThrowable(IOException("permission denied /tmp/a"), R.string.error_load_host)
        val formatted = message.resolve { id, args ->
            when (id) {
                R.string.error_load_host -> "无法加载主机"
                R.string.error_with_detail -> "${args[0]}：${args[1]}"
                else -> error("Unexpected resource")
            }
        }
        assertEquals("无法加载主机：permission denied /tmp/a", formatted)
    }

    @Test fun blankThirdPartyMessageUsesOnlyTheFallback() {
        assertEquals(UserMessage(R.string.error_unknown),
            UserMessage.fromThrowable(IOException("  "), R.string.error_unknown))
    }

    @Test fun causeCyclesDoNotHangTheErrorRenderer() {
        val first = IOException("first")
        val second = IOException("second", first)
        first.initCause(second)
        assertEquals(R.string.error_with_detail,
            UserMessage.fromThrowable(first, R.string.error_unknown).resourceId)
    }
}
