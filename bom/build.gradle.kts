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
// Locked under ADR-016 alongside lockstep versioning. Constraint strings use the
// published coordinates (tessera-<module>) rather than `project(":<module>")` —
// java-platform's constraint resolution on `project(...)` would record the Gradle
// project name, not the published artifactId vanniktech overrides it to.
dependencies {
    constraints {
        api("${project.group}:tessera-types:${project.version}")
        api("${project.group}:tessera-mrz-core:${project.version}")
        api("${project.group}:tessera-emrtd-core:${project.version}")
        api("${project.group}:tessera-telemetry:${project.version}")
        api("${project.group}:tessera-logging:${project.version}")
        api("${project.group}:tessera-mrz-camera-core:${project.version}")
        api("${project.group}:tessera-mrz-camera-android:${project.version}")
    }
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
