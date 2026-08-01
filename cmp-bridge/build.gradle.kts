@file:OptIn(ExperimentalWasmDsl::class)

import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.android.kotlin.multiplatform.library")
    id("dev.detekt")
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
}

dependencies {
    detektPlugins("dev.detekt:detekt-rules-ktlint-wrapper:_")
}

kotlin {
    jvmToolchain((findProperty("jdkVersion") as String).toInt())

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

tasks.named<Test>("jvmTest") {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
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
