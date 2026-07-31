@file:OptIn(ExperimentalWasmDsl::class)

import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.android.library)
    alias(libs.plugins.detekt)
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
}

dependencies {
    detektPlugins(libs.detekt.formatting)
}

kotlin {
    jvmToolchain(21)

    android {
        namespace = "com.cramsan.cmpbridge"
        compileSdk = (findProperty("compileSdkVersion") as String).toInt()
        minSdk = (findProperty("minSdkVersion") as String).toInt()

        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        }

        androidResources.enable = true
    }

    jvm()

    wasmJs {
        browser()
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.serialization.json)
        }

        jvmMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(compose.desktop.currentOs)
        }

        jvmTest.dependencies {
            implementation(libs.kotlin.test.junit5)
            implementation(libs.junit.jupiter.api)
            implementation(libs.junit.jupiter.params)
            runtimeOnly(libs.junit.jupiter.engine)
            runtimeOnly(libs.junit.platform.launcher)
        }
    }
}

tasks.named<Test>("jvmTest") {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}

// On a Kotlin Multiplatform module, detekt 2.0's Gradle plugin registers one analysis task per
// source set/compilation (detektJvmMainSourceSet, detektWasmJsMainSourceSet, ...) instead of
// wiring them into the plain "detekt" task the way it does for a single-target module — that
// umbrella task reports NO-SOURCE and `check`/`build` only depend on it, so real source sets get
// silently skipped unless it's told about them explicitly here.
tasks.named("detekt") {
    dependsOn(
        tasks.matching {
            it.name.startsWith("detekt") && it.name.endsWith("SourceSet") && "Baseline" !in it.name
        },
    )
}
