// Android app. Open this folder in Android Studio (Ladybug or newer).
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

rootProject.name = "airbox-android"
include(":app")
// The shared transfer engine (plain Kotlin/JVM) from the repository root.
include(":core")
project(":core").projectDir = file("../core")
