plugins {
    id("org.jetbrains.kotlin.jvm") apply false
    id("org.jetbrains.kotlin.multiplatform") apply false
    id("org.jetbrains.kotlin.plugin.serialization") apply false
    id("org.jetbrains.kotlin.plugin.compose") apply false
    id("com.android.kotlin.multiplatform.library") apply false
    id("org.jetbrains.compose") apply false
    id("io.ktor.plugin") apply false
    id("dev.detekt") apply false
    id("com.vanniktech.maven.publish") apply false
    id("com.gradleup.shadow") apply false
}

// Single source of truth for every module's Maven coordinates (GROUP/VERSION_NAME live in
// gradle.properties). Plain Project properties, so — unlike the publishing plugin's own DSL —
// this is safe to centralize here without any classloader/plugin-resolution concerns.
allprojects {
    group = property("GROUP") as String
    version = property("VERSION_NAME") as String
}
