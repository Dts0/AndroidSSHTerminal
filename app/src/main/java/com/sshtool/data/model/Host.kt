package com.sshtool.data.model

import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.PrimaryKey

@Entity(tableName = "hosts")
data class Host(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val host: String,
    val port: Int = 22,
    val username: String,
    val useKeyAuth: Boolean = false,
    val lastConnected: Long? = null,
    val createdAt: Long = System.currentTimeMillis()
) {
    @Ignore
    var password: String? = null

    @Ignore
    var privateKey: String? = null

    @Ignore
    var passphrase: String? = null
}

