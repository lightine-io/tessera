package io.lightine.tessera.mrz

import io.lightine.tessera.mrz.parsing.MrzParser
import java.io.DataInputStream
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The published JVM classes are Java 17 bytecode (class file major version 61), so an app that
 * builds with JDK 17 can use the SDK. The build compiles with a JDK 21 toolchain; the target is
 * set in the `tessera.kotlin-multiplatform` convention plugin.
 */
class PublishedBytecodeVersionTest {
    @Test
    fun main_classes_are_java_17_bytecode() {
        val resource = "/" + MrzParser::class.java.name.replace('.', '/') + ".class"
        val major =
            DataInputStream(checkNotNull(MrzParser::class.java.getResourceAsStream(resource))).use { stream ->
                stream.skipBytes(CLASS_FILE_MAGIC_AND_MINOR_BYTES)
                stream.readUnsignedShort()
            }
        assertEquals(JAVA_17_CLASS_FILE_MAJOR, major, "class file major version of MrzParser")
    }

    private companion object {
        const val CLASS_FILE_MAGIC_AND_MINOR_BYTES = 6
        const val JAVA_17_CLASS_FILE_MAJOR = 61
    }
}
