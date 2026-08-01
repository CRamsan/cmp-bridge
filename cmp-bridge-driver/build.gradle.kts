plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("dev.detekt")
    id("com.vanniktech.maven.publish")
}

description = "BridgeDriver client for driving a running Compose Multiplatform app's UI bridge directly."

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

// Maven Central publishing — see RELEASING.md. Duplicated verbatim across every published module
// (matching this repo's own house style for small, must-stay-in-sync blocks — see the
// BridgeExplorerOptions duplication between cmp-bridge-http-server/cmp-bridge-mcp-server): Gradle
// has no classloader-safe way to share a plugins{}-DSL-resolved plugin's own typed extension
// config across build scripts without buildSrc, and buildSrc hits its own version of that problem
// specifically for a Kotlin Multiplatform module. mavenPublishing {} is the plugin's own generated
// Kotlin DSL accessor — no import needed, since it only resolves in a script that applies the
// plugin itself via the plugins {} block above. group/version come from the root project's
// allprojects {} (GROUP/VERSION_NAME in gradle.properties); coordinates() isn't called since the
// plugin already defaults to project.group/project.name/project.version.
mavenPublishing {
    // Targets the Central Publisher Portal (the only host that exists now — Sonatype's legacy
    // OSSRH was fully shut down). Left at its default of leaving each deployment "pending" in the
    // Portal UI rather than publishToMavenCentral(automaticRelease = true), so every release still
    // gets a manual sanity check before it becomes permanent.
    publishToMavenCentral()
    signAllPublications()

    pom {
        name.set(project.name)
        description.set(
            project.description
                ?: error("Set `description = \"...\"` in this module's build.gradle.kts before publishing."),
        )
        url.set("https://github.com/CRamsan/cmp-bridge")

        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }

        developers {
            developer {
                id.set("cramsan")
                name.set("Cesar Ramirez")
                email.set("contact@cramsan.com")
            }
        }

        scm {
            url.set("https://github.com/CRamsan/cmp-bridge")
            connection.set("scm:git:git://github.com/CRamsan/cmp-bridge.git")
            developerConnection.set("scm:git:ssh://git@github.com/CRamsan/cmp-bridge.git")
        }
    }
}
