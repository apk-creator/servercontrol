rootProject.name = "ServerControl"

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

include(":composeApp")
// ConnectBot terminal with a small patch (see termlib/README.md)
include(":termlib")
