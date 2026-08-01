plugins {
    application
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("io.ktor.plugin")
    id("dev.detekt")
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

    // No trailing ":_" on these: their version comes from the io.ktor.plugin Gradle plugin's own
    // BOM-style alignment (applied above), not from an explicit version anywhere — matching how
    // the version catalog declared them with no version.ref either. Don't add a placeholder here.
    implementation("io.ktor:ktor-server-core-jvm")
    implementation("io.ktor:ktor-server-netty-jvm")
    implementation("io.ktor:ktor-server-content-negotiation")
    implementation("io.ktor:ktor-server-call-logging")
    implementation("io.ktor:ktor-server-status-pages")
    implementation("io.ktor:ktor-serialization-kotlinx-json")
    implementation("com.github.ajalt.clikt:clikt:_")

    detektPlugins("dev.detekt:detekt-rules-ktlint-wrapper:_")

    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5:_")
    testImplementation("org.junit.jupiter:junit-jupiter-api:_")
    testImplementation("org.junit.jupiter:junit-jupiter-params:_")
    testImplementation("io.ktor:ktor-server-test-host")
    testImplementation("io.ktor:ktor-client-content-negotiation")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:_")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:_")
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
