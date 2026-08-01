plugins {
    application
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("dev.detekt")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of((findProperty("jdkVersion") as String).toInt()))
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

    implementation("io.modelcontextprotocol:kotlin-sdk:_")
    implementation("org.jetbrains.kotlinx:kotlinx-io-core:_")
    implementation("com.github.ajalt.clikt:clikt:_")

    detektPlugins("dev.detekt:detekt-rules-ktlint-wrapper:_")

    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5:_")
    testImplementation("org.junit.jupiter:junit-jupiter-api:_")
    testImplementation("org.junit.jupiter:junit-jupiter-params:_")
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
    archiveBaseName.set("cmp-bridge-mcp-server")
}

// Same duplicate-jar cause as cmp-bridge-http-server/build.gradle.kts.
tasks.withType<AbstractCopyTask>().configureEach {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
