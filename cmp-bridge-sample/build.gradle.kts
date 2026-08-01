@file:OptIn(ExperimentalWasmDsl::class)

import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
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
    jvmToolchain(21)

    jvm()

    wasmJs {
        outputModuleName.set("cmpBridgeSample")
        browser()
        binaries.executable()
    }

    sourceSets {
        commonMain.dependencies {
            // Direct coordinates, not compose.runtime/.foundation/.material3/.ui: those accessors
            // are deprecated in compose-multiplatform 1.10.3. material3 tracks its own version,
            // independent of the rest of Compose Multiplatform.
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
    useJUnitPlatform()
    // WasmDevServerProcess.launch shells out to this repo's own gradlew — it needs to know where
    // that is, since the test JVM's working directory isn't guaranteed to be the repo root.
    systemProperty("e2e.repoRoot", rootProject.projectDir.absolutePath)
    testLogging {
        events("passed", "skipped", "failed")
    }
}

// See the equivalent comment in cmp-bridge/build.gradle.kts — same cause (detekt 2.0's Gradle
// plugin doesn't wire per-source-set KMP analysis tasks into the plain "detekt" task itself).
tasks.named("detekt") {
    dependsOn(
        tasks.matching {
            it.name.startsWith("detekt") && it.name.endsWith("SourceSet") && "Baseline" !in it.name
        },
    )
}
