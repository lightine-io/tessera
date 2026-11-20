plugins {
    // Gradle's own plugin for writing convention plugins as Kotlin scripts
    // (src/main/kotlin/tessera.*.gradle.kts).
    `kotlin-dsl`
}

// The third-party plugins the convention plugins apply, at the versions pinned in
// gradle/libs.versions.toml. The root build.gradle.kts still declares them with `apply false`, so
// every module loads them from the same class loader.
dependencies {
    implementation(pluginArtifact(libs.plugins.kotlin.multiplatform))
    implementation(pluginArtifact(libs.plugins.android.kotlin.multiplatform.library))
    implementation(pluginArtifact(libs.plugins.dokka))
    implementation(pluginArtifact(libs.plugins.maven.publish))
}

// A plugin's marker artifact (`<id>:<id>.gradle.plugin:<version>`), which brings in the plugin's
// implementation.
fun pluginArtifact(plugin: Provider<PluginDependency>): Provider<String> =
    plugin.map { "${it.pluginId}:${it.pluginId}.gradle.plugin:${it.version.requiredVersion}" }
