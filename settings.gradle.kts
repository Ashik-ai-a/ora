pluginManagement {
    repositories {
        // Google's Maven Central mirror first: it is more reliable than repo1 from CI proxies.
        maven("https://maven-central.storage-download.googleapis.com/maven2")
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        maven("https://maven-central.storage-download.googleapis.com/maven2")
        mavenCentral()
    }
}

rootProject.name = "airbox"
include(":core", ":cli", ":tools:android-typecheck")
