// Sample for the page TES-A-76, Read your first MRZ on Android with the ready-made screen: https://lightine.youtrack.cloud/articles/TES-A-76. The region is the whole file the page shows.
// region TES-A-76 block 3
package com.example.firstmrz

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.lightine.tessera.mrz.camera.ui.MrzScannerConfig
import io.lightine.tessera.mrz.camera.ui.MrzScannerResult
import io.lightine.tessera.mrz.camera.ui.MrzScannerScreen
import io.lightine.tessera.mrz.parsing.ParseResult

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { FirstMrzApp() }
    }
}

@Composable
fun FirstMrzApp() {
    var showScanner by rememberSaveable { mutableStateOf(false) }
    var summary by rememberSaveable { mutableStateOf("No scan yet") }
    val requestCamera = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* the scanner checks the permission again when the app resumes */ }

    if (showScanner) {
        MrzScannerScreen(
            config = MrzScannerConfig {
                onRequestPermission = { requestCamera.launch(Manifest.permission.CAMERA) }
            },
            onResult = { result ->
                summary = when (result) {
                    is MrzScannerResult.Confirmed -> summarize(result.result.parse)
                    is MrzScannerResult.Cancelled -> "Closed: ${result.reason}"
                }
                showScanner = false
            },
        )
    } else {
        Column(Modifier.padding(24.dp)) {
            Button(onClick = { showScanner = true }) { Text("Scan a document") }
            Text(summary)
        }
    }
}

private fun summarize(parse: ParseResult): String {
    val document = when (parse) {
        is ParseResult.Success -> parse.document
        is ParseResult.PartialSuccess -> parse.document
        is ParseResult.Failure -> return "Not a readable MRZ"
    }
    val fields = document.commonFields
    return "Name: ${fields.primaryIdentifier}, ${fields.secondaryIdentifier}\n" +
        "Document number: ${fields.documentNumber}\n" +
        "Format: ${document.format}\n" +
        "Read by: ${parse.metadata.readMethod}"
}
// endregion
