package io.lightine.tessera.emrtd.fakechip

/** No crypto backend on iOS yet (OpenSSL is planned), so the signing tests skip here. */
internal actual fun testSigner(): TestSigner? = null
