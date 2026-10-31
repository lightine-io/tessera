---
paths:
  - "**/build.gradle.kts"
  - "settings.gradle.kts"
  - "gradle/libs.versions.toml"
  - "gradle/wrapper/gradle-wrapper.properties"
  - ".github/workflows/**"
  - "**/androidMain/**"
  - "**/iosMain/**"
  - "**/appleMain/**"
  - "**/androidHostTest/**"
  - "**/androidInstrumentedTest/**"
  - "**/iosTest/**"
  - "mrz-camera-*/**"
---

# Platform and build work

## Read the vendor's current docs first

Platform code, dependencies, build files, and CI use technology we did not write. Read
the vendor's current documentation before you write, choose, or upgrade, even when you
feel sure: confidence is not evidence. After two or three failed attempts, stop guessing
and read. The docs feed your analysis; they can be stale or wrong, so check them against
what you observe.

## Record the stack

When you add or remove a dependency, plugin, tool, or CI action, name the row change in
the PR: a PR cannot edit a KB page, so the maintainer updates the tech-stack index
([TES-A-23](https://lightine.youtrack.cloud/articles/TES-A-23)). A row is: Type · Vendor · Docs (official, opened today) · Source (where the version is
pinned, never the number) · Added (release) · Docs-verified (today).

## Drive with the prescribed tools

- Android: the Android CLI (`android sdk`, `avd`, `emulator`, `run`, `layout`), and
  `adb` for text (`logcat`, `pm grant`, `input`).
- iOS: the Xcode MCP (`BuildProject`, diagnostics, run). Confirm its tools exist before
  iOS work; if they are missing, tell the owner (the server likely needs a restart).
  `xcrun devicectl` for the device console.
- Builds and tests: `./gradlew`.

A Bash hook (`scripts/prefer-dev-wrappers.sh`) blocks the raw vendor tool where a driver
owns the domain: `sdkmanager`, `avdmanager`, a raw `emulator`, and `adb install` (use the
Android CLI); `xcodebuild` and `xcrun simctl` (use the Xcode MCP). Plain `adb` text uses
and `xcrun devicectl` are not guarded. The override is a trailing `# raw-ok` comment at the
end of the command; `raw-ok` anywhere else does not count. Use it only when the driver
cannot do the job.

Raw uses that need no discussion (add `# raw-ok`):

- `xcodebuild` with environment variables, for example `TESSERA_LOCAL_XCFRAMEWORK=1`.
- `xcodebuild test` with a simulator destination (the MCP cannot pick one).
- During device QA: `xcrun simctl ui <udid> appearance` and `content_size`, and
  `xcrun simctl addmedia` (QA playbook, [TES-A-72](https://lightine.youtrack.cloud/articles/TES-A-72)).

Any other raw vendor tool: say why in the session before you add `# raw-ok`. MCP gaps: TES-44.

## Text first; images only small

Read state as text: `adb logcat`, `android layout` or `uiautomator dump`, test and Gradle
output, Xcode MCP diagnostics. A full-size screenshot can end the session, so a hook
blocks capture commands in Bash. MCP tools that return screenshots are outside the
hook: do not use them for full-size captures. The one image path: the human records the
screen, and you read downscaled contact sheets
(`ffmpeg ... fps=1/N,scale=400:-1,tile=4x2`), as the QA playbook describes.

## Testing layers

- Host (JVM): glue logic with injected frames and a mock OCR.
- Emulator or simulator: real OCR on a still image. The iOS Simulator has no camera.
- Physical device: the only place a live lens is validated.
- NFC: emulators and simulators have none. Add the NFC layer to the testing page
  ([TES-A-15](https://lightine.youtrack.cloud/articles/TES-A-15)) before the first NFC PR.
