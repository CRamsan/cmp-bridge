plugins {
    application
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktor.plugin)
    alias(libs.plugins.detekt)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

application {
    mainClass.set("com.cramsan.cmpbridge.httpserver.MainKt")
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
}

dependencies {
    api(project(":cmp-bridge-driver"))

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.clikt)

    detektPlugins(libs.detekt.formatting)

    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.junit.jupiter.api)
    testImplementation(libs.junit.jupiter.params)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.content.negotiation)
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
    archiveBaseName.set("cmp-bridge-http-server")
}

ktor {
    fatJar {
        archiveFileName.set("cmp-bridge-http-server-all.jar")
    }
}

// Pulling in :cmp-bridge-driver drags Compose Desktop's own dependency graph along (it depends on
// :cmp-bridge, a Compose Multiplatform module), which has a couple of jars resolving to the same
// file name via different coordinates — harmless for the fat jar we actually ship (buildFatJar),
// but the `application` plugin's distTar/distZip tasks refuse to proceed without an explicit
// duplicates policy.
tasks.withType<AbstractCopyTask>().configureEach {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
