plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

kotlin {
    // Java 17 bytecode keeps the core consumable from Android (AGP 8.x).
    jvmToolchain(21)
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.78.1")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
