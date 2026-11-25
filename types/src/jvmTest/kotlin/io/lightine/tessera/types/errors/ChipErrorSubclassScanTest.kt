@file:OptIn(ExperimentalTesseraChipApi::class)

package io.lightine.tessera.types.errors

import io.lightine.tessera.types.ExperimentalTesseraChipApi
import java.io.File
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every concrete [ChipError] subclass compiled into the errors package appears in
 * [ChipErrorSamples.every], so the code walk in `ChipErrorCommonFactsTest` cannot miss one.
 */
class ChipErrorSubclassScanTest {
    @Test
    fun everyCompiledChipErrorSubclassIsSampled() {
        val root =
            File(
                ChipError::class.java.protectionDomain.codeSource.location
                    .toURI(),
            )
        assertTrue(root.isDirectory, "the types classes are a directory in this build: $root")
        val dir =
            File(
                root,
                ChipError::class.java.`package`.name
                    .replace('.', '/'),
            )
        val compiled =
            dir
                .listFiles { f -> f.name.endsWith(".class") }
                .orEmpty()
                .map { Class.forName("${ChipError::class.java.`package`.name}.${it.name.removeSuffix(".class")}") }
                .filter { ChipError::class.java.isAssignableFrom(it) && !Modifier.isAbstract(it.modifiers) }
                .map { it.name }
                .toSet()
        assertTrue(compiled.size >= 11, "found $compiled")
        assertEquals(compiled, ChipErrorSamples.every.map { it::class.java.name }.toSet())
    }
}
