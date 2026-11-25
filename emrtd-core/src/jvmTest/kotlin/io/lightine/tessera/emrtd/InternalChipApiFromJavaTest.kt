package io.lightine.tessera.emrtd

import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * TES-346: the read coordinator and the observer hand-over are Tessera's own (InternalTesseraChipApi).
 * The opt-in marker does not reach Java, so on the JVM nothing of them may be callable from Java:
 * no public constructor, and every public method synthetic (javac cannot call a synthetic method).
 */
class InternalChipApiFromJavaTest {
    private fun assertNothingCallableFromJava(type: Class<*>) {
        // The compiler's accessor constructor for the companion's factory is synthetic too.
        val constructors = type.constructors.filterNot { it.isSynthetic }
        assertTrue(constructors.isEmpty(), "${type.simpleName}: public constructors $constructors")
        val callable =
            type.declaredMethods.filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic && !it.isBridge }
                // Interface methods the platform calls through ChipReadObserver: harmless without an instance.
                .filterNot { it.name == "onUpdate" || it.name == "onEnding" }
        assertTrue(callable.isEmpty(), "${type.simpleName}: callable from Java: $callable")
    }

    @Test
    fun theCoordinatorAndItsFactoryAreNotCallableFromJava() {
        assertNothingCallableFromJava(ChipReadCoordinator::class.java)
        assertNothingCallableFromJava(ChipReadCoordinator.Companion::class.java)
    }

    @Test
    fun theHandOverObserverAndItsFactoryAreNotCallableFromJava() {
        assertNothingCallableFromJava(HandoffObserver::class.java)
        assertNothingCallableFromJava(HandoffObserver.Companion::class.java)
    }
}
