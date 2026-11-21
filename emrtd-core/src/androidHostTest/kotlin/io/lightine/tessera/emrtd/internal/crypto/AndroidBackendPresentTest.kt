package io.lightine.tessera.emrtd.internal.crypto

import kotlin.test.Test
import kotlin.test.assertSame

/** The Android target has the BouncyCastle backend, so the common vector suite really runs here. */
class AndroidBackendPresentTest {
    @Test
    fun androidUsesBouncyCastle() {
        assertSame(BcChipCrypto, chipCrypto())
    }
}
