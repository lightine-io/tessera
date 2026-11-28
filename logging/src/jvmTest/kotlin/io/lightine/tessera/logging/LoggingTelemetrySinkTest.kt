package io.lightine.tessera.logging

import io.lightine.tessera.telemetry.TelemetryEvent
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals

class LoggingTelemetrySinkTest {
    private class FakeEvent : TelemetryEvent {
        override val name: String = "fake.event"

        override fun toString(): String = "FakeEvent(count=3)"
    }

    private fun capture(block: () -> Unit): String {
        val original = System.out
        val buffer = ByteArrayOutputStream()
        System.setOut(PrintStream(buffer, true, "UTF-8"))
        try {
            block()
        } finally {
            System.setOut(original)
        }
        return buffer.toString("UTF-8")
    }

    @Test
    fun printsTheNameAndTheEventTextWithTheTag() {
        val out = capture { LoggingTelemetrySink("MyTag").record(FakeEvent()) }
        assertEquals("MyTag: fake.event FakeEvent(count=3)" + System.lineSeparator(), out)
    }

    @Test
    fun defaultTagIsTessera() {
        val out = capture { LoggingTelemetrySink().record(FakeEvent()) }
        assertEquals("Tessera: fake.event FakeEvent(count=3)" + System.lineSeparator(), out)
    }

    @Test
    fun printsOneLinePerEvent() {
        val out = capture {
            val sink = LoggingTelemetrySink()
            sink.record(FakeEvent())
            sink.record(FakeEvent())
        }
        assertEquals(2, out.lines().count { it.isNotEmpty() })
    }
}
