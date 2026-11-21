import org.gradle.api.publish.maven.MavenPublication

plugins {
    id("tessera.publishing")
}

// Keep the old 1.0.0 coordinate available as a Maven relocation POM. This module publishes no
// code or platform artifacts; it only directs consumers to the renamed OCR core.
val publicationGroup = group.toString()
val publicationVersion = version.toString()
publishing {
    publications {
        create<MavenPublication>("relocation") {
            groupId = publicationGroup
            artifactId = "tessera-mrz-camera-core"
            version = publicationVersion

            pom {
                name.set("tessera-mrz-camera-core")
                description.set(
                    "Relocation to tessera-mrz-ocr-core, the Tessera core for camera and saved-image OCR.",
                )
                distributionManagement {
                    relocation {
                        groupId.set(publicationGroup)
                        artifactId.set("tessera-mrz-ocr-core")
                        version.set(publicationVersion)
                        message.set("The module was renamed to tessera-mrz-ocr-core.")
                    }
                }
            }
        }
    }
}
