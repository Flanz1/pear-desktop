// Plugins are put on one root classpath so the Kotlin and Android Gradle
// plugins can see each other. With -PcoreOnly the Android plugin is left out
// (it comes from Google's Maven repository, which may be unavailable).
buildscript {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    dependencies {
        classpath(libs.gradle.kotlin)
        classpath(libs.gradle.kotlin.compose)
        if (!providers.gradleProperty("coreOnly").isPresent) {
            classpath(libs.gradle.android)
        }
    }
}
