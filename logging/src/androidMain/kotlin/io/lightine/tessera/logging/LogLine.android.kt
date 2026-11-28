package io.lightine.tessera.logging

import android.util.Log

internal actual fun writeLogLine(tag: String, line: String) {
    Log.d(tag, line)
}
