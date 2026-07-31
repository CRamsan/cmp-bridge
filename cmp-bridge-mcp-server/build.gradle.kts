plugins {
    application
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.detekt)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

application {
    mainClass.set("com.cramsan.cmpbridge.mcpserver.MainKt")
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
}

dependencies {
    api(project(":cmp-bridge-driver"))

    implementation(libs.mcp.kotlin.sdk)
    implementation(libs.kotlinx.io.core)
    implementation(libs.clikt)

    detektPlugins(libs.detekt.formatting)

    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.junit.jupiter.api)
    testImplementation(libs.junit.jupiter.params)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.named<Test>("test") {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}

tasks.named<Jar>("jar") {
    archiveBaseName.set("cmp-bridge-mcp-server")
}

// See the equivalent comment in cmp-bridge-http-server/build.gradle.kts — same cause
// (:cmp-bridge-driver drags Compose Desktop's dependency graph along). Covers both the archive
// tasks (distTar/distZip) and the Sync-based installDist staging task.
tasks.withType<AbstractCopyTask>().configureEach {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
