package io.lightine.tessera.emrtd.internal.crypto

// No backend yet: the OpenSSL backend (libcrypto through cinterop) replaces this in a later change.
internal actual fun chipCrypto(): ChipCrypto = throw ChipCryptoUnavailableException()
