plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("dev.detekt")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of((findProperty("jdkVersion") as String).toInt()))
    }
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
}

dependencies {
    api(project(":cmp-bridge-driver"))

    // This module is a library, not an application, so it doesn't apply the io.ktor.plugin
    // Gradle plugin the way cmp-bridge-http-server does (that plugin unconditionally wires up
    // shadowJar/dist*/startScripts tasks aimed at a runnable app, which fail without a mainClass).
    // Ktor client artifacts are version-placeholder'd like any other dependency instead.
    implementation("io.ktor:ktor-client-core:_")
    implementation("io.ktor:ktor-client-cio:_")
    implementation("io.ktor:ktor-client-content-negotiation:_")
    implementation("io.ktor:ktor-serialization-kotlinx-json:_")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:_")

    detektPlugins("dev.detekt:detekt-rules-ktlint-wrapper:_")

    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5:_")
    testImplementation("org.junit.jupiter:junit-jupiter-api:_")
    testImplementation("org.junit.jupiter:junit-jupiter-params:_")
    testImplementation("io.ktor:ktor-client-mock:_")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:_")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:_")
}

tasks.named<Test>("test") {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}
