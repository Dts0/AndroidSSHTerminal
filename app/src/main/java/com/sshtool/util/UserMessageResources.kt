package com.sshtool.util

import android.content.Context

fun Context.resolveMessage(message: UserMessage): String =
    message.resolve { resourceId, args -> getString(resourceId, *args) }
