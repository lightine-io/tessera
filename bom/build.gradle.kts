plugins {
    `java-platform`
    alias(libs.plugins.maven.publish)
}

// BOM (Bill of Materials) — a POM-only artifact that declares the version of every
// Tessera module. Consumers `import` it to align module versions automatically:
//
//   dependencies {
//       implementation(platform("io.lightine.tessera:tessera-bom:0.1.1"))
//       implementation("io.lightine.tessera:tessera-mrz-core")  // no version
//       implementation("io.lightine.tessera:tessera-types")     // no version
//   }
//
// Locked under ADR-016 alongside lockstep versioning.
//
// The BOM lists, for every published module, the root id plus its `-jvm` and `-android` ids
// (TES-204), as the kotlinx-coroutines, kotlinx-serialization and Ktor BOMs do. Maven users who
// name a platform artifact, and Gradle builds that pin one, then get the version from the BOM.
// The list is derived from the build: every Maven publication named `kotlinMultiplatform`
// (the root), `jvm` or `android` in a subproject that applies the vanniktech plugin. Native
// (iOS klib) publications are left out, as kotlinx-coroutines-bom does: Gradle picks them from
// the root's metadata, and Maven cannot use them.
//
// The ids are read in `projectsEvaluated` because vanniktech sets each publication's artifactId
// (`tessera-<module>`, `tessera-<module>-jvm`, ...) only after the module's own build file runs;
// earlier, the ids are still the Gradle project names. Constraint strings use the published
// coordinates rather than `project(":<module>")` for the same reason.
val bomPublicationNames = setOf("kotlinMultiplatform", "jvm", "android")
gradle.projectsEvaluated {
    rootProject.subprojects
        .filter { it != project && it.plugins.hasPlugin("com.vanniktech.maven.publish") }
        .flatMap { module ->
            module.extensions
                .getByType<PublishingExtension>()
                .publications
                .withType<MavenPublication>()
                .filter { it.name in bomPublicationNames }
                .map { "${it.groupId}:${it.artifactId}:${it.version}" }
        }.sorted()
        .forEach { dependencies.constraints.add("api", it) }
}

mavenPublishing {
    coordinates(group.toString(), "tessera-bom", version.toString())

    pom {
        name.set("tessera-bom")
        description.set(
            "Bill of Materials (BOM) for the Tessera identity-document SDK. Import via " +
                "`platform(\"io.lightine.tessera:tessera-bom:<version>\")` to align " +
                "versions across all Tessera modules without specifying per-module " +
                "versions. Updated in lockstep with the rest of the SDK per ADR-016.",
        )
    }
}
