@file:OptIn(ExperimentalWasmDsl::class)

import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    jvmToolchain(21)

    jvm()

    wasmJs {
        outputModuleName.set("cmpBridgeSample")
        browser()
        binaries.executable()
    }

    sourceSets {
        commonMain.dependencies {
            // compose.runtime/.foundation/.material3/.ui are deprecated in compose-multiplatform
            // 1.10.3 in favor of direct coordinates. Versions pinned to what this Compose release
            // actually ships (material3 tracks its own version, independent of the rest).
            implementation("org.jetbrains.compose.runtime:runtime:${libs.versions.compose.multiplatform.get()}")
            implementation("org.jetbrains.compose.foundation:foundation:${libs.versions.compose.multiplatform.get()}")
            implementation("org.jetbrains.compose.material3:material3:1.9.0")
            implementation("org.jetbrains.compose.ui:ui:${libs.versions.compose.multiplatform.get()}")
        }

        jvmMain.dependencies {
            implementation(project(":cmp-bridge"))
            implementation(compose.desktop.currentOs)
        }

        jvmTest.dependencies {
            implementation(project(":cmp-bridge-driver"))
            implementation(libs.kotlin.test.junit5)
            implementation(libs.junit.jupiter.api)
            implementation(libs.junit.jupiter.params)
            runtimeOnly(libs.junit.jupiter.engine)
            runtimeOnly(libs.junit.platform.launcher)
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.cramsan.cmpbridge.sample.MainKt"
    }
}

tasks.named<Test>("jvmTest") {
    useJUnitPlatform()
    // WasmDevServerProcess.launch shells out to this repo's own gradlew — it needs to know where
    // that is, since the test JVM's working directory isn't guaranteed to be the repo root.
    systemProperty("e2e.repoRoot", rootProject.projectDir.absolutePath)
    testLogging {
        events("passed", "skipped", "failed")
    }
}
