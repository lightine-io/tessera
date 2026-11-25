# Tessera

[![Maven Central](https://img.shields.io/maven-central/v/io.lightine.tessera/tessera-bom?label=Maven%20Central)](https://central.sonatype.com/artifact/io.lightine.tessera/tessera-bom)
[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
[![CI](https://github.com/lightine-io/tessera/actions/workflows/check.yml/badge.svg)](https://github.com/lightine-io/tessera/actions/workflows/check.yml)

Tessera is a free, open-source SDK that reads, validates, and generates the data in the MRZ (machine-readable zone) of passports, ID cards, and visas, and, as experimental API, the data on a passport's chip over NFC. It runs on Android, iOS, and the JVM. It reports what it read and checked; your app decides what to do with that. The SDK never says a document is valid or genuine: there is no `isValid`, and a result with no failures means only that the checks it ran passed.

It is for developers adding document reading to apps or backends. Documentation: [Start here](https://lightine.youtrack.cloud/articles/TES-A-16). API reference: [lightine-io.github.io/tessera](https://lightine-io.github.io/tessera/) (available from the 1.0.0 release).

## Common tasks

- Ready-made scanner screen. Android: [Read your first MRZ on Android with the ready-made screen](https://lightine.youtrack.cloud/articles/TES-A-76). iOS: [Read your first MRZ on iOS with the ready-made screen](https://lightine.youtrack.cloud/articles/TES-A-77).
- Camera behind your own UI. Android: [Read your first MRZ on Android with the headless camera](https://lightine.youtrack.cloud/articles/TES-A-142). iOS: [Read your first MRZ on iOS with the headless camera](https://lightine.youtrack.cloud/articles/TES-A-143).
- Parse an MRZ string. JVM and Kotlin: [Read your first MRZ on the JVM](https://lightine.youtrack.cloud/articles/TES-A-10). iOS: [Parse an MRZ string on iOS](https://lightine.youtrack.cloud/articles/TES-A-113).
- Read an MRZ from a saved photo. Android: [Read an MRZ from a saved image on Android](https://lightine.youtrack.cloud/articles/TES-A-64). iOS: [Read an MRZ from a saved image on iOS](https://lightine.youtrack.cloud/articles/TES-A-116).
- Generate test MRZ data. Kotlin: [Generate an MRZ in Kotlin](https://lightine.youtrack.cloud/articles/TES-A-26).

## What works where

✓ yes, ◐ partial, ✗ no, n/a not applicable. All rows: [Capability matrix](https://lightine.youtrack.cloud/articles/TES-A-139).

| Capability | JVM | Android | iOS |
|---|---|---|---|
| Parse and read fields | ✓ | ✓ | ◐ |
| Validate and check digits | ✓ | ✓ | ✓ |
| Generate an MRZ | ✓ | ✓ | ◐ |
| Transliterate names | ✓ | ✓ | ✓ |
| Live camera, no UI | n/a | ✓ | ◐ |
| Saved image | ◐ | ✓ | ✓ |
| Ready-made scanner screen | n/a | ✓ | ✓ |
| Diagnostic events | ✓ | ✓ | ✓ |

Tessera's own code makes no network calls and logs nothing. On Android, the text recognition (Google ML Kit) uploads usage metrics to Google, never images or text; an app that must make no network calls can turn this off. See [SECURITY.md](SECURITY.md#scope).

## Install

Release 0.7.0. Minimums: minSdk 23. Java 17 and iOS 15 from the next release; 0.7.0 needs Java 21 and iOS 18. Modules: [Choose your pieces](https://lightine.youtrack.cloud/articles/TES-A-140).

`build.gradle.kts` (the JVM needs only the first two lines):

```kotlin
dependencies {
    implementation(platform("io.lightine.tessera:tessera-bom:0.7.0"))
    implementation("io.lightine.tessera:tessera-mrz-core")
    implementation("io.lightine.tessera:tessera-mrz-camera")
    implementation("io.lightine.tessera:tessera-mrz-camera-ui")
}
```

`pom.xml` (JVM, Maven):

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>io.lightine.tessera</groupId>
            <artifactId>tessera-bom</artifactId>
            <version>0.7.0</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
<dependencies>
    <dependency>
        <groupId>io.lightine.tessera</groupId>
        <artifactId>tessera-mrz-core</artifactId>
    </dependency>
</dependencies>
```

`Package.swift` (iOS):

```swift
.package(url: "https://github.com/lightine-io/tessera-swift", exact: "0.7.0"),
.product(name: "Tessera", package: "tessera-swift"),
.product(name: "TesseraUI", package: "tessera-swift"),
```

## Scanner screen in a few lines

Android, Kotlin, Jetpack Compose. This is the ready-made screen: it shows the camera, finds the MRZ, and hands your app the parsed result.

`app/build.gradle.kts`:

```kotlin
plugins {
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21"
}

android {
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform("io.lightine.tessera:tessera-bom:0.7.0"))
    implementation("io.lightine.tessera:tessera-mrz-camera-ui")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.material3:material3:1.4.0")
}
```

`app/src/main/AndroidManifest.xml`: Tessera declares no permission, so your app does.

```xml
<uses-permission android:name="android.permission.CAMERA" />
```

The screen reads whether the permission is held and never requests it. You hand the request to your own code through `onRequestPermission`:

```kotlin
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
```

iOS: the Swift package has a ready-made SwiftUI screen, `TesseraUI`. See [Read your first MRZ on iOS with the ready-made screen](https://lightine.youtrack.cloud/articles/TES-A-77).

## Read a chip (headless) in a few lines

Experimental. Chip reading is not in release 0.7.0; the dependency lines below use version 1.0.0, as the compiled samples do. The chip API can change in any release before 1.0, and you opt in once. The SDK reports the files on the chip and the facts its checks found. It never says the document is valid or genuine, and it sends no write command.

Android. `app/build.gradle.kts`:

```kotlin
dependencies {
    implementation(platform("io.lightine.tessera:tessera-bom:1.0.0"))
    implementation("io.lightine.tessera:tessera-emrtd-nfc")
}

kotlin {
    compilerOptions {
        optIn.add("io.lightine.tessera.types.ExperimentalTesseraChipApi")
    }
}
```

`app/src/main/AndroidManifest.xml`: the library adds the `NFC` permission itself; the feature line is yours (use `android:required="true"` if the app cannot work without NFC).

```xml
<uses-feature android:name="android.hardware.nfc" android:required="false" />
```

`NfcChipRead.start` owns the link to the chip: it turns NFC reader mode on for your screen, reads, checks, turns reader mode off at the ending, and clears the key. Your observer gets updates and exactly one ending, on the main thread. Make a new key for every read, and never retry automatically after a refused key: ask the person to check the values.

```kotlin
import android.app.Activity
import io.lightine.tessera.emrtd.ChipAccessKey
import io.lightine.tessera.emrtd.ChipCheckKind
import io.lightine.tessera.emrtd.ChipRead
import io.lightine.tessera.emrtd.ChipReadEnding
import io.lightine.tessera.emrtd.ChipReadHandle
import io.lightine.tessera.emrtd.ChipReadObserver
import io.lightine.tessera.emrtd.ChipReadOptions
import io.lightine.tessera.emrtd.ChipReadUpdate
import io.lightine.tessera.emrtd.ChipSignedFile
import io.lightine.tessera.emrtd.MrzKeyResult
import io.lightine.tessera.emrtd.nfc.NfcChipRead
import io.lightine.tessera.mrz.model.MrzDocument
import io.lightine.tessera.types.errors.ChipAccessDenied
import io.lightine.tessera.types.errors.ChipError
import io.lightine.tessera.types.errors.NfcTagLost
import kotlin.time.Clock

// show(...) stands in for your own screen code.

fun accessKeyFrom(document: MrzDocument): ChipAccessKey.Mrz? =
    when (val result = ChipAccessKey.Mrz.from(document)) {
        is MrzKeyResult.Built -> result.key
        is MrzKeyResult.NotBuilt -> {
            show("No key could be made: ${result.problem}") // for example a visa, which has no chip
            null
        }
    }

fun startRead(activity: Activity, key: ChipAccessKey): ChipReadHandle {
    val options = ChipReadOptions(accessKey = key, checkTime = Clock.System.now())
    // Turns reader mode on for this screen, runs the read, turns it off at the ending.
    return NfcChipRead.start(activity, options, ReadScreen)
}

object ReadScreen : ChipReadObserver {
    // Both calls come on the main thread, in order, and nothing comes before start returns.
    override fun onUpdate(update: ChipReadUpdate) = show("${update.state} ${update.progress.percent}%")

    override fun onEnding(ending: ChipReadEnding) = handle(ending) // exactly once, and last
}

fun handle(ending: ChipReadEnding) {
    when (ending) {
        is ChipReadEnding.Complete -> showFacts(ending.read)
        is ChipReadEnding.Partial -> {
            showFacts(ending.read) // what was read before the stop
            show(describe(ending.stoppedBy))
        }
        is ChipReadEnding.Failed -> show(describe(ending.error))
        is ChipReadEnding.NotStarted -> show("NFC cannot be used: ${ending.reason}")
        else -> show("The read ended: $ending") // cancelled, key rejected, ended by the system
    }
}

fun describe(error: ChipError): String = when (error) {
    is NfcTagLost -> "The document moved. Hold it still against the phone."
    is ChipAccessDenied -> "The chip did not accept the MRZ values."
    else -> error.description
}

fun showFacts(read: ChipRead) {
    val hashOfDg1 = read.outcomeOf(ChipCheckKind.DataGroupHashMatches(1))
    val signature = read.outcomeOf(ChipCheckKind.SignatureVerifies(ChipSignedFile.SECURITY_OBJECT))
    show("DG1 hash: $hashOfDg1, signature: $signature, files: ${read.files.size}")
}
```

To leave the screen, call `read.cancel()` on the handle. To check the chip's signature, pass your own root certificates: `ChipReadOptions.Builder(key).trustAnchors(TrustAnchors.load(file).anchors)`.

iOS. Chip reading is in the Swift package as the product `TesseraKit`, not in the 0.7.0 package. Add `.product(name: "TesseraKit", package: "tessera-swift")`, and in the app's Info.plist the NFC entries and entitlement (a paid Apple developer team is needed). The shortest use:

```swift
let options = try ChipReadOptions.Builder(accessKey: key).build()
let reading = ChipReading.start(options: options)
if case .complete(let read) = await reading.ending.outcome { /* use read */ }
```

## Go further

[Tutorials](https://lightine.youtrack.cloud/articles/TES-A-141) · [How-to guides](https://lightine.youtrack.cloud/articles/TES-A-88) · [Reference](https://lightine.youtrack.cloud/articles/TES-A-135) · [Explanation](https://lightine.youtrack.cloud/articles/TES-A-83). Changes per release: [`CHANGELOG.md`](CHANGELOG.md).

Questions and bugs: [GitHub issues](https://github.com/lightine-io/tessera/issues). Vulnerabilities: report privately, see [`SECURITY.md`](SECURITY.md). To contribute: [`CONTRIBUTING.md`](CONTRIBUTING.md). Licence: Apache License 2.0 ([`LICENSE`](LICENSE)).
