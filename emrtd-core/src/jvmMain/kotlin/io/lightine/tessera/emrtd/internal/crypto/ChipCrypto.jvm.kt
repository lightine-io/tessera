package io.lightine.tessera.emrtd.internal.crypto

// BcChipCrypto lives in src/bouncyCastleMain, compiled into both the JVM and the Android target.
internal actual fun chipCrypto(): ChipCrypto = BcChipCrypto
