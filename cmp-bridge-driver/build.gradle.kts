plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("dev.detekt")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
}

dependencies {
    api(project(":cmp-bridge"))

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:_")
    implementation("com.microsoft.playwright:playwright:_")

    detektPlugins("dev.detekt:detekt-rules-ktlint-wrapper:_")

    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5:_")
    testImplementation("org.junit.jupiter:junit-jupiter-api:_")
    testImplementation("org.junit.jupiter:junit-jupiter-params:_")
    testImplementation("io.mockk:mockk:_")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:_")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:_")
}

tasks.named<Test>("test") {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}
