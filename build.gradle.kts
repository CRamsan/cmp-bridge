import org.gradle.api.plugins.JavaPluginExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

plugins {
    id("org.jetbrains.kotlin.jvm") apply false
    id("org.jetbrains.kotlin.multiplatform") apply false
    id("org.jetbrains.kotlin.plugin.serialization") apply false
    id("org.jetbrains.kotlin.plugin.compose") apply false
    id("com.android.kotlin.multiplatform.library") apply false
    id("org.jetbrains.compose") apply false
    id("io.ktor.plugin") apply false
    id("dev.detekt") apply false
    id("com.vanniktech.maven.publish") apply false
    id("com.gradleup.shadow") apply false
}

// Single source of truth for every module's Maven coordinates (GROUP/VERSION_NAME live in
// gradle.properties). Plain Project properties, so — unlike the publishing plugin's own DSL —
// this is safe to centralize here without any classloader/plugin-resolution concerns.
allprojects {
    group = property("GROUP") as String
    version = property("VERSION_NAME") as String
}

// Config shared across every subproject, centralized here instead of duplicated per module. Safe
// to do from the root script (unlike mavenPublishing {} — see the comment in
// cmp-bridge/build.gradle.kts): this only configures extensions/tasks each module already applies
// itself via its own plugins {} block, so it shares that plugin's classloader rather than routing
// its *application* through a separate one (buildSrc), which is what actually breaks for plugins
// that introspect other plugins' classes (vanniktech's maven-publish does; detekt/toolchain don't).
subprojects {
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging {
            events("passed", "skipped", "failed")
        }
    }

    val jdkVersion = findProperty("jdkVersion") as String

    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        extensions.configure<JavaPluginExtension> {
            toolchain {
                languageVersion.set(JavaLanguageVersion.of(jdkVersion.toInt()))
            }
        }
    }

    pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
        extensions.configure<KotlinMultiplatformExtension> {
            jvmToolchain(jdkVersion.toInt())
        }
    }

    // :cmp-bridge-driver pulls in Compose Desktop, which resolves a couple of jars to the same
    // file name — harmless for the CLI modules' fat jars, but distTar/distZip need an explicit
    // duplicates policy. Scoped to the `application` plugin since only cmp-bridge-http-server and
    // cmp-bridge-mcp-server apply it.
    pluginManager.withPlugin("application") {
        tasks.withType<AbstractCopyTask>().configureEach {
            duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        }
    }
}
