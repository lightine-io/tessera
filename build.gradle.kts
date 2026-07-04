import com.vanniktech.maven.publish.JavaPlatform
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinMultiplatform
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import kotlinx.validation.api.dump
import kotlinx.validation.api.filterOutNonPublic
import kotlinx.validation.api.loadApiFromJvmClasses
import java.util.jar.JarFile

// The android-only ABI guard (registerAndroidAbiTasks, below) uses binary-compatibility-validator +
// asm-tree + kotlin-metadata-jvm. Those are declared in buildSrc/build.gradle.kts (buildSrc's runtime
// classpath reaches this script), NOT on this project's buildscript classpath — so the build-time
// classpath is never exposed to the submitted dependency graph (dependency-review gates only shipped
// deps). See buildSrc/build.gradle.kts and .github/workflows/dependency-submission.yml.

plugins {
    alias(libs.plugins.spotless)
    alias(libs.plugins.kotlin.multiplatform) apply false
    // Android target for the core modules via Google's KMP-library plugin (ADR-017). Declared here
    // with `apply false` so the alias is available to subprojects; each core module applies it.
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
    alias(libs.plugins.maven.publish) apply false
    alias(libs.plugins.dokka) apply false
}

allprojects {
    group = "io.lightine.tessera"
}

// Shared Maven Central POM metadata (license, url, developer, scm) applied to every
// subproject that has the vanniktech maven-publish plugin applied. Per-module identity
// (artifactId, pom name, pom description) is set in each module's build.gradle.kts.
//
// Two publication shapes are supported:
//   - KMP modules (types, mrz-core, emrtd-core, telemetry, logging) get
//     `configure(KotlinMultiplatform(...))` with Dokka-backed javadoc jars and sources jars.
//     JavadocJar.Dokka points at Dokka 2's `dokkaGeneratePublicationHtml` task — Maven Central
//     requires a `*-javadoc.jar` for non-snapshot releases.
//   - The BOM module (java-platform) gets `configure(JavaPlatform())` — no jar, no sources,
//     no javadoc, just a POM with `<dependencyManagement>` per ADR-016's BOM decision.
//
// Coordinates and publication scope locked under ADR-016.
subprojects {
    plugins.withId("com.vanniktech.maven.publish") {
        extensions.configure<MavenPublishBaseExtension> {
            // Target the Sonatype Central Portal (central.sonatype.com) for `publishToMavenCentral`.
            // No SonatypeHost argument: as of the vanniktech plugin 0.36.x, the no-arg / boolean-only
            // form defaults to the Central Portal (the old OSSRH `S01`/`DEFAULT` hosts are legacy).
            // `automaticRelease = false` uploads the artifacts as a deployment that sits VALIDATED
            // but unpublished — a maintainer manually clicks "Publish" in the portal UI to release it
            // to Maven Central. Coordinates lock under ADR-007 only at that manual release, not at
            // upload, so a staged deployment can be dropped and redone freely.
            publishToMavenCentral(automaticRelease = false)

            // Sign every published artifact (jar, sources, javadoc, pom, module, and the BOM
            // pom) with the maintainer's PGP key. Maven Central rejects non-snapshot releases
            // that lack a `.asc` signature for each artifact. vanniktech wires up the Gradle
            // signing plugin automatically when this is called; credentials are read from
            // `signingInMemoryKey` / `signingInMemoryKeyId` / `signingInMemoryKeyPassword`
            // (set per-machine in `~/.gradle/gradle.properties`, never committed — see
            // `docs/publishing-setup.md`).
            //
            // `tessera.skipSigning` is the explicit escape hatch for the `publish-smoke` CI
            // guard (check.yml): that job runs `publishToMavenLocal` on keyless PR runners to
            // prove every declared target actually produces its artifacts (the v0.2.0
            // empty-module incident class, PR #154 — the release dry-run SKIPs the
            // generateMetadataFile* tasks where that class fails, so only a real local publish
            // exercises them). With signing unconditional, a keyless publishToMavenLocal dies
            // in signMavenPublication before reaching the tasks the guard exists to run.
            // Signing stays REQUIRED on every path that does not explicitly pass the property:
            // a real publish that somehow lost its key still fails loudly (here, or at Central
            // Portal validation, which rejects unsigned deployments).
            if (!providers.gradleProperty("tessera.skipSigning").isPresent) {
                signAllPublications()
            }

            pom {
                url.set("https://github.com/lightine-io/tessera")
                inceptionYear.set("2026")

                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                        distribution.set("repo")
                    }
                }

                developers {
                    developer {
                        id.set("askerasadov")
                        name.set("Asker Asadov")
                        email.set("asker.asadov@gmail.com")
                    }
                }

                scm {
                    url.set("https://github.com/lightine-io/tessera")
                    connection.set("scm:git:git://github.com/lightine-io/tessera.git")
                    developerConnection.set("scm:git:ssh://git@github.com/lightine-io/tessera.git")
                }

                issueManagement {
                    system.set("GitHub")
                    url.set("https://github.com/lightine-io/tessera/issues")
                }
            }
        }

        plugins.withId("org.jetbrains.kotlin.multiplatform") {
            extensions.configure<MavenPublishBaseExtension> {
                configure(
                    KotlinMultiplatform(
                        javadocJar = JavadocJar.Dokka("dokkaGeneratePublicationHtml"),
                        sourcesJar = true,
                    ),
                )
            }
        }

        plugins.withId("java-platform") {
            extensions.configure<MavenPublishBaseExtension> {
                configure(JavaPlatform())
            }
        }
    }
}

spotless {
    val ktlintVersion = libs.versions.ktlint.get()
    kotlin {
        target("**/*.kt")
        targetExclude("**/build/**", "**/.gradle/**")
        ktlint(ktlintVersion)
    }
    kotlinGradle {
        target("**/*.gradle.kts")
        targetExclude("**/build/**", "**/.gradle/**")
        ktlint(ktlintVersion)
    }
}

// --- Android-only ABI guard (TES-35) ------------------------------------------------------------
// Kotlin's abiValidation does not dump the AGP-KMP `android {}` target, so the two modules whose public
// API lives ONLY in androidMain — mrz-camera-android (CameraXMrzScanner, MlKitMrzTextRecognizer, the
// saved-image/EXIF factories) and mrz-camera-ui-android (MrzScannerScreen / MrzScannerConfig /
// MrzScannerResult) — have no machine-checked baseline; their checkKotlinAbi passes vacuously. This
// guards that surface the way every other module is guarded: it dumps the android target's COMPILED
// BYTECODE with binary-compatibility-validator (the engine behind abiValidation) into a committed
// api/android/<module>.api baseline, and fails `check` on any undeclared change (an ADR-007 break).
// Refresh after an intended change: `./gradlew :<module>:updateAndroidAbi` and commit the baseline.
// The core modules are NOT in this set: their public API is common (already baselined via the klib/jvm
// dumps); only these two have android-specific public declarations.
val androidAbiGuardedModules = setOf("mrz-camera-android", "mrz-camera-ui-android")

fun Project.registerAndroidAbiTasks() {
    // Capture the project name here (project scope): inside a task's configuration/`doLast`, `name` is
    // the TASK's name, not the module's — so user-facing strings must use this, not `$moduleName`.
    val moduleName = name
    val baseline = file("api/android/$moduleName.api")
    // The android target's compiled-classes jar (KMP-AGP library plugin output). Reading it needs no
    // Android SDK — only the compile task that writes it does — so these tasks run wherever the android
    // target compiles (the android-compile CI job + locally).
    val bundleTask = "bundleAndroidMainClassesToCompileJar"
    val classesJar = file("build/intermediates/compile_library_classes_jar/androidMain/$bundleTask/classes.jar")

    fun dumpTo(target: java.io.File) {
        val raw = StringBuilder()
        JarFile(classesJar)
            .loadApiFromJvmClasses()
            .filterOutNonPublic()
            .dump(raw)
        // Drop the Compose compiler's `ComposableSingletons$…` lambda-holder classes: they are
        // compiler-generated implementation detail (not consumer API), and their `getLambda$<hash>`
        // members carry a position-derived hash that can shift across compose-compiler versions —
        // baselining them would produce spurious ABI breaks. (metalava filters these too.) Filtered at
        // the text level because ClassBinarySignature.name is `internal` in BCV; the dump is a series of
        // blank-line-separated class blocks, so a block whose header line names ComposableSingletons is
        // dropped whole.
        val filtered =
            raw
                .toString()
                .split("\n\n")
                .map { it.trim('\n') }
                .filter { it.isNotBlank() && "ComposableSingletons" !in it.substringBefore('\n') }
                .joinToString(separator = "\n\n", postfix = "\n")
        target.parentFile.mkdirs()
        target.writeText(filtered)
    }

    tasks.register("updateAndroidAbi") {
        group = "verification"
        description = "Regenerates the committed android public-ABI baseline (api/android/$moduleName.api)."
        dependsOn(bundleTask)
        outputs.file(baseline)
        doLast { dumpTo(baseline) }
    }

    // NOT wired into `check`: like verifyMlKitBundledModel, checkAndroidAbi compiles the android target,
    // so it runs explicitly in the android-compile CI job (which provisions the Android SDK), not on the
    // SDK-less JVM `check` job. Run it (and updateAndroidAbi) locally against a module directly.
    tasks.register("checkAndroidAbi") {
        group = "verification"
        description = "Fails if the android public ABI changed vs the committed api/android/$moduleName.api."
        dependsOn(bundleTask)
        inputs.file(classesJar)
        // The committed baseline is read in `doLast` but deliberately NOT declared as a formal input: it is
        // `updateAndroidAbi`'s output, and declaring it here would make Gradle demand a dependency between
        // the two (they must stay independent — the check compares against the COMMITTED baseline, never a
        // freshly regenerated one). A verification task always running is fine.
        doLast {
            val committed = baseline
            if (!committed.exists()) {
                throw GradleException(
                    "No android ABI baseline for '$moduleName'. Run `./gradlew :$moduleName:updateAndroidAbi` and " +
                        "commit api/android/$moduleName.api.",
                )
            }
            val current = file("build/android-abi/$moduleName.api")
            dumpTo(current)
            if (current.readText() != committed.readText()) {
                throw GradleException(
                    "Android public ABI of '$moduleName' changed vs api/android/$moduleName.api (the AGP-KMP android " +
                        "target, outside Kotlin's abiValidation — TES-35). If intended, run " +
                        "`./gradlew :$moduleName:updateAndroidAbi` and commit the updated baseline; otherwise it is " +
                        "an ADR-007 backward-compatibility break.",
                )
            }
        }
    }
}

subprojects {
    if (name in androidAbiGuardedModules) registerAndroidAbiTasks()
}
