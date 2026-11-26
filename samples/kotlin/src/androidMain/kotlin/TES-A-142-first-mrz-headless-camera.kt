// Sample for the page TES-A-142, Read your first MRZ on Android with the headless camera: https://lightine.youtrack.cloud/articles/TES-A-142. The region is the whole file the page shows.
// region TES-A-142 block 2
package com.example.cameraheadless

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import io.lightine.tessera.mrz.camera.CameraXMrzScanner
import io.lightine.tessera.mrz.camera.MrzScanResult
import io.lightine.tessera.mrz.parsing.ParseResult
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

private const val TAG = "Tessera"

class MainActivity : ComponentActivity() {
    private val scope = MainScope()
    private lateinit var scanner: CameraXMrzScanner

    private val requestCamera =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) scanner.start() else Log.i(TAG, "Camera permission denied")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scanner = CameraXMrzScanner(applicationContext, this)

        scope.launch {
            scanner.results.collect { result ->
                if (result !is MrzScanResult.Decoded) return@collect
                // This accepts the first decoded frame. The how-to on accepting a read after N frames shows agreement.
                val parse = result.parse
                val document = when (parse) {
                    is ParseResult.Success -> parse.document
                    is ParseResult.PartialSuccess -> parse.document
                    is ParseResult.Failure -> return@collect
                }
                // Show the name and document number on your own screen; never write them to the log.
                Log.i(TAG, "Format: ${document.format}")
                Log.i(TAG, "Check digits passed: ${parse is ParseResult.Success}")
                scanner.stop()
            }
        }

        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            scanner.start()
        } else {
            requestCamera.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        scanner.close()
    }
}
// endregion
