package io.lightine.tessera.mrz.camera.ui

import android.util.Rational
import androidx.camera.core.ViewPort
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * Host-side test for the resize path of the live preview (TES-119): the preview area's viewport and the
 * scanner bound with it. The real [CameraXMrzScanner] needs a camera, so a fake scanner stands in; what
 * CameraX does with the viewport on a real bind is checked on a device (QA scenario), not here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ViewportScannerTest {
    @get:Rule
    val composeRule = createComposeRule()

    private class FakeScanner(
        val viewport: ViewPort?,
    ) {
        var started = false
        var closed = false
    }

    private val made = mutableListOf<FakeScanner>()
    private var size by mutableStateOf(DpSize(100.dp, 200.dp))
    private var rotation by mutableIntStateOf(0)

    private fun setUp() {
        composeRule.setContent {
            Box(Modifier.size(size)) {
                BoxWithConstraints {
                    val viewport = rememberPreviewViewport(constraints, rotation)
                    rememberViewportScanner<FakeScanner>(
                        viewport = viewport,
                        context = LocalContext.current,
                        lifecycleOwner = LocalLifecycleOwner.current,
                        create = { FakeScanner(it).also(made::add) },
                        onStart = { it.started = true },
                        onClose = { it.closed = true },
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun a_window_that_never_resizes_keeps_one_running_scanner() {
        setUp()

        assertEquals(1, made.size)
        val only = made.single()
        assertEquals(true, only.started)
        assertEquals(false, only.closed)
        assertEquals(Rational(1, 2), only.viewport?.aspectRatio)
    }

    @Test
    fun a_resize_to_a_new_aspect_ratio_restarts_the_scanner_with_the_new_viewport() {
        setUp()
        val first = made.single()

        // Split screen turns a tall preview area into a wide one.
        size = DpSize(200.dp, 100.dp)
        composeRule.waitForIdle()

        assertEquals(2, made.size)
        assertEquals(true, first.closed)
        val second = made.last()
        assertEquals(true, second.started)
        assertEquals(false, second.closed)
        assertEquals(Rational(2, 1), second.viewport?.aspectRatio)
    }

    @Test
    fun a_rotation_change_restarts_the_scanner_with_the_new_rotation() {
        setUp()

        rotation = 1
        composeRule.waitForIdle()

        assertEquals(2, made.size)
        assertEquals(true, made.first().closed)
        assertEquals(1, made.last().viewport?.rotation)
    }

    @Test
    fun a_resize_that_keeps_the_aspect_ratio_keeps_the_scanner() {
        setUp()
        val first = made.single()

        size = DpSize(150.dp, 300.dp)
        composeRule.waitForIdle()

        assertSame(first, made.single())
        assertEquals(false, first.closed)
    }
}
