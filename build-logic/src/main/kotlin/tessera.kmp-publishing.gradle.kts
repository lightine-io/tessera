// A Kotlin Multiplatform module published to Maven Central: Dokka for the javadoc jar, then the
// shared publishing setup. Dokka must be applied before the publishing plugin: signAllPublications()
// (tessera.publishing) forces eager realization of the Dokka javadoc jar while the publishing plugin is
// applied, which looks up the `dokkaGeneratePublicationHtml` task, so that task must already exist.
plugins {
    id("tessera.kotlin-multiplatform")
    id("org.jetbrains.dokka")
    id("tessera.publishing")
}
