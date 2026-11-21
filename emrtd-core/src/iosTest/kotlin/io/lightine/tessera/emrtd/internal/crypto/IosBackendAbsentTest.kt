package io.lightine.tessera.emrtd.internal.crypto

import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * iOS has no backend until OpenSSL lands, and says so with a typed error. The common vector suite
 * skips itself here (withCrypto); when the backend lands, this test gives way to the suite running.
 */
class IosBackendAbsentTest {
    @Test
    fun iosHasNoBackendYet() {
        assertFailsWith<ChipCryptoUnavailableException> { chipCrypto() }
    }
}
