package io.lightine.tessera.samples.scanner

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.lightine.tessera.mrz.camera.ui.MrzScannerConfig
import io.lightine.tessera.mrz.camera.ui.MrzScannerResult
import io.lightine.tessera.mrz.camera.ui.MrzScannerScreen
import io.lightine.tessera.mrz.model.TD3
import io.lightine.tessera.mrz.parsing.ParseResult

/** Opens the ready-made scanner screen and shows what it returns. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { SampleApp() } }
    }
}

@Suppress("ktlint:standard:function-naming") // a composable is named like a type
@Composable
private fun SampleApp() {
    var showScanner by rememberSaveable { mutableStateOf(false) }

    // The summary holds document data, so it lives in memory only: not in saved instance state, not in a log.
    var summary by remember { mutableStateOf("No scan yet") }

    val requestCamera =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { /* the scanner checks the permission again when the app resumes */ }

    if (showScanner) {
        MrzScannerScreen(
            config =
                MrzScannerConfig {
                    onRequestPermission = { requestCamera.launch(Manifest.permission.CAMERA) }
                },
            onResult = { result ->
                summary =
                    when (result) {
                        is MrzScannerResult.Confirmed -> describe(result.result.parse)
                        is MrzScannerResult.Cancelled -> "Closed: ${result.reason}"
                    }
                showScanner = false
            },
        )
    } else {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Button(onClick = { showScanner = true }) { Text("Scan a document") }
            Text(summary, Modifier.padding(top = 16.dp))
        }
    }
}

/** One line per field, as read. The text is only displayed; the sample never logs or stores it. */
private fun describe(parse: ParseResult): String {
    val document =
        when (parse) {
            is ParseResult.Success -> parse.document
            is ParseResult.PartialSuccess -> parse.document
            is ParseResult.Failure -> return "Not a readable MRZ"
        }
    val fields = document.commonFields
    return buildString {
        appendLine("Format: ${document.format}")
        appendLine("Document type: ${fields.documentType.rawCode}")
        appendLine("Issuing state: ${fields.issuingState.rawCode}")
        appendLine("Name: ${fields.primaryIdentifier}, ${fields.secondaryIdentifier}")
        appendLine("Document number: ${fields.documentNumber}")
        appendLine("Nationality: ${fields.nationality.rawCode}")
        appendLine("Date of birth: ${fields.dateOfBirth.computedDate}")
        appendLine("Sex: ${fields.rawSex}")
        appendLine("Date of expiry: ${fields.dateOfExpiry.computedDate}")
        if (document is TD3) appendLine("Personal number: ${document.personalNumber}")
        appendLine("Check digits: ${fields.checkDigits}")
        append("Read by: ${parse.metadata.readMethod}")
        if (parse is ParseResult.PartialSuccess) append("\nA check digit did not agree.")
    }
}
