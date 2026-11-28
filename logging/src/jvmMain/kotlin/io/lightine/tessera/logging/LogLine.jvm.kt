package io.lightine.tessera.logging

internal actual fun writeLogLine(tag: String, line: String) {
    println("$tag: $line")
}
