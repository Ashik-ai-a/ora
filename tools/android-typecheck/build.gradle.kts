// Type-checks the Android app's platform layer (framework APIs only, no AndroidX) against
// the real Android 16 framework classes, since the Android SDK itself can't be downloaded
// in every CI environment. This catches wrong API names, signatures and nullability.
// It does not build an APK; see android/README.md for that.
plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    compileOnly("org.robolectric:android-all:16-robolectric-13921718")
    implementation(project(":core"))
    implementation("com.google.zxing:core:3.5.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
}

sourceSets {
    main {
        kotlin.srcDir("../../android/app/src/main/java/app/airbox/android/platform")
    }
}
