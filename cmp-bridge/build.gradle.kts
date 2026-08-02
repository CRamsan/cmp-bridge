@file:OptIn(ExperimentalWasmDsl::class)

import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.android.kotlin.multiplatform.library")
    id("dev.detekt")
    id("com.vanniktech.maven.publish")
}

description = "In-app UI automation bridge for Compose Multiplatform apps."

detekt {
    buildUponDefaultConfig = true
    config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
}

dependencies {
    detektPlugins("dev.detekt:detekt-rules-ktlint-wrapper:_")
}

kotlin {
    android {
        namespace = "com.cramsan.cmpbridge"
        compileSdk = (findProperty("compileSdkVersion") as String).toInt()
        minSdk = (findProperty("minSdkVersion") as String).toInt()

        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.fromTarget(findProperty("jdkVersion") as String))
        }

        androidResources.enable = true
    }

    jvm()

    wasmJs {
        browser()
    }

    sourceSets {
        commonMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:_")
        }

        jvmMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:_")
            implementation(compose.desktop.currentOs)
        }

        jvmTest.dependencies {
            implementation("org.jetbrains.kotlin:kotlin-test-junit5:_")
            implementation("org.junit.jupiter:junit-jupiter-api:_")
            implementation("org.junit.jupiter:junit-jupiter-params:_")
            runtimeOnly("org.junit.jupiter:junit-jupiter-engine:_")
            runtimeOnly("org.junit.platform:junit-platform-launcher:_")
        }
    }
}

// detekt 2.0's Gradle plugin registers one analysis task per KMP source set instead of wiring
// them into the plain "detekt" task, which otherwise reports NO-SOURCE and skips them.
tasks.named("detekt") {
    dependsOn(
        tasks.matching {
            it.name.startsWith("detekt") && it.name.endsWith("SourceSet") && "Baseline" !in it.name
        },
    )
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
