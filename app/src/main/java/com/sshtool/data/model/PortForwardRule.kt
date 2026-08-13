package com.sshtool.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class PortForwardDirection {
    LOCAL,
    REMOTE
}

/**
 * A port-forward configuration owned by one saved [Host]. Runtime state is
 * deliberately not persisted here; it belongs to the concrete SSH session
 * that attempted to start this rule.
 */
@Entity(
    tableName = "port_forward_rules",
    foreignKeys = [
        ForeignKey(
            entity = Host::class,
            parentColumns = ["id"],
            childColumns = ["hostId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["hostId"])]
)
data class PortForwardRule(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val hostId: Long = 0,
    val direction: PortForwardDirection,
    val bindAddress: String = "127.0.0.1",
    val listenPort: Int,
    val targetHost: String = "127.0.0.1",
    val targetPort: Int,
    val enabled: Boolean = true,
    val sortOrder: Int = 0
)

/** Match the distinct bind-address semantics used by JSch 0.2.18. */
internal fun portForwardBindAddressesConflict(
    direction: PortForwardDirection,
    left: String,
    right: String
): Boolean = when (direction) {
    PortForwardDirection.LOCAL -> {
        val leftKey = left.localPortForwardBindKey()
        val rightKey = right.localPortForwardBindKey()
        leftKey == rightKey || leftKey.isLocalWildcard() || rightKey.isLocalWildcard()
    }
    PortForwardDirection.REMOTE ->
        // ChannelForwardedTCPIP only aliases '*' (and the API's empty value)
        // to the SSH protocol's empty bind address. In particular, do not
        // collapse that value with 0.0.0.0 or other server-side spellings.
        left.remotePortForwardBindKey() == right.remotePortForwardBindKey()
}

private fun String.portForwardAddressComparisonKey(): String {
    var normalized = trim().lowercase()
    if (normalized.length >= 2 && normalized.first() == '[' && normalized.last() == ']') {
        normalized = normalized.substring(1, normalized.lastIndex)
    }
    if (':' !in normalized) normalized = normalized.removeSuffix(".")
    return normalized
}

private fun String.localPortForwardBindKey(): String =
    when (val key = portForwardAddressComparisonKey()) {
        "*" -> "0.0.0.0"
        "localhost" -> "127.0.0.1"
        "0:0:0:0:0:0:0:0" -> "::"
        "0:0:0:0:0:0:0:1" -> "::1"
        else -> key
    }

private fun String.remotePortForwardBindKey(): String {
    val key = trim().lowercase()
    return if (key == "*") "" else key
}

private fun String.isLocalWildcard(): Boolean = this == "0.0.0.0" || this == "::"
