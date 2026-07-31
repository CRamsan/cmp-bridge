pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
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
