plugins {
    id("org.jetbrains.kotlin.jvm") version "1.9.22"
    application
}
repositories { mavenCentral() }
dependencies {
    implementation("org.json:json:20231013")
}
application {
    mainClass.set("TestMainKt")
}
