package io.lightine.tessera.emrtd

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

// clear() comes from the thread that delivers the ending while the read thread may be taking a
// copy: a copy is the whole secret or refused, never half wiped, and none is taken after clear().
class ChipAccessKeyClearRaceTest {
    @Test
    fun a_copy_racing_clear_is_whole_or_refused_and_none_follows_it() {
        repeat(500) {
            val key = ChipAccessKey.Can("123456")
            val expected = key.secretCopy()
            val start = CountDownLatch(1)
            val failure = AtomicReference<String?>(null)
            val readers =
                List(4) {
                    thread {
                        start.await()
                        while (true) {
                            val copy =
                                try {
                                    key.secretCopy()
                                } catch (_: IllegalStateException) {
                                    break
                                }
                            if (!copy.contentEquals(expected)) failure.compareAndSet(null, "a half-wiped copy")
                        }
                    }
                }
            start.countDown()
            key.clear()
            assertTrue(key.isCleared)
            assertTrue(key.secret.all { it.toInt() == 0 })
            assertFailsWith<IllegalStateException> { key.secretCopy() }
            readers.forEach { it.join() }
            assertNull(failure.get())
        }
    }
}
