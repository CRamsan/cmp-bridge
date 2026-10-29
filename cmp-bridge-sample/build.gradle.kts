@file:OptIn(ExperimentalWasmDsl::class)

import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    id("org.jetbrains.kotlin.multiplatform")
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
    // The shared demo UI as an Android library target; the runnable Android app lives in
    // :cmp-bridge-sample-android. AGP 9 no longer allows com.android.application and the KMP
    // plugin in the same module, hence the split (desktop and web stay in-module as before).
    android {
        namespace = "com.cramsan.cmpbridge.sample"
        compileSdk = (findProperty("compileSdkVersion") as String).toInt()
        minSdk = (findProperty("minSdkVersion") as String).toInt()

        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.fromTarget(findProperty("jdkVersion") as String))
        }
    }

    jvm()

    wasmJs {
        outputModuleName.set("cmpBridgeSample")
        browser()
        binaries.executable()
    }

    sourceSets {
        commonMain.dependencies {
            // Direct coordinates, not compose.runtime/.foundation/.material3/.ui — those accessors
            // are deprecated in this Compose Multiplatform version.
            implementation("org.jetbrains.compose.runtime:runtime:_")
            implementation("org.jetbrains.compose.foundation:foundation:_")
            implementation("org.jetbrains.compose.material3:material3:_")
            implementation("org.jetbrains.compose.ui:ui:_")
            implementation("org.jetbrains.compose.ui:ui-tooling-preview:_")
        }

        jvmMain.dependencies {
            implementation(project(":cmp-bridge"))
            implementation(compose.desktop.currentOs)
        }

        jvmTest.dependencies {
            implementation(project(":cmp-bridge-driver"))
            implementation("org.jetbrains.kotlin:kotlin-test-junit5:_")
            implementation("org.junit.jupiter:junit-jupiter-api:_")
            implementation("org.junit.jupiter:junit-jupiter-params:_")
            runtimeOnly("org.junit.jupiter:junit-jupiter-engine:_")
            runtimeOnly("org.junit.platform:junit-platform-launcher:_")
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.cramsan.cmpbridge.sample.MainKt"
    }
}

tasks.named<Test>("jvmTest") {
    // This module's own convention for getting the repo root into the test JVM — DemoScenarioTest
    // and BridgeDriverStressTest read it themselves to build the command they hand to
    // WasmDevServerProcess.launch, which has no notion of Gradle or a repo root of its own (#14).
    systemProperty("e2e.repoRoot", rootProject.projectDir.absolutePath)

    // The Android case installs the sample APK itself via adb. It's built here, as a task
    // dependency, rather than by a nested `./gradlew installDebug` from inside the test: a nested
    // build of this same project contends with the outer one's locks and has been seen to stall
    // for ~10 minutes before proceeding.
    val androidApk = project(":cmp-bridge-sample-android").layout.buildDirectory
        .file("outputs/apk/debug/cmp-bridge-sample-android-debug.apk")
    dependsOn(":cmp-bridge-sample-android:assembleDebug")
    systemProperty("e2e.androidApk", androidApk.get().asFile.absolutePath)
}

// See the equivalent comment in cmp-bridge/build.gradle.kts.
tasks.named("detekt") {
    dependsOn(
        tasks.matching {
            it.name.startsWith("detekt") && it.name.endsWith("SourceSet") && "Baseline" !in it.name
        },
    )
}
