plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("dev.detekt")
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
}

// The sample's runnable Android app: a thin host activity around the shared App() from
// :cmp-bridge-sample. It's a separate module because AGP 9 no longer allows
// com.android.application and the Kotlin Multiplatform plugin in the same module. MainActivity arms
// AndroidBridgeServer when launched with `--ez cmpBridge.enabled true`, mirroring the desktop sample.
android {
    namespace = "com.cramsan.cmpbridge.sample.android"
    compileSdk = (findProperty("compileSdkVersion") as String).toInt()

    defaultConfig {
        applicationId = "com.cramsan.cmpbridge.sample"
        minSdk = (findProperty("minSdkVersion") as String).toInt()
        targetSdk = (findProperty("compileSdkVersion") as String).toInt()
        versionCode = 1
        versionName = project.version.toString()
    }
}

dependencies {
    implementation(project(":cmp-bridge-sample"))
    implementation(project(":cmp-bridge"))
    implementation("androidx.activity:activity-compose:_")
    implementation("org.jetbrains.compose.runtime:runtime:_")

    detektPlugins("dev.detekt:detekt-rules-ktlint-wrapper:_")
}
