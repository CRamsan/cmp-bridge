pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("de.fayard.refreshVersions") version "0.60.6"
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "cmp-bridge-root"

include(":cmp-bridge")
include(":cmp-bridge-driver")
include(":cmp-bridge-http-server")
include(":cmp-bridge-mcp-server")
include(":cmp-bridge-sample")
