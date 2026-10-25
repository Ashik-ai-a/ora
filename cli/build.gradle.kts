plugins {
    kotlin("jvm")
    application
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":core"))
    implementation("com.google.zxing:core:3.5.3")
}

application {
    mainClass.set("app.airbox.cli.MainKt")
    applicationName = "airbox"
}
