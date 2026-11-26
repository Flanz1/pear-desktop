pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "pear-remote"

// :core is plain Kotlin/JVM (protocol, pairing, TLS pinning, connection) and
// builds without the Android SDK. Pass -PcoreOnly to skip :app, for example
// where Google's Maven repository or the SDK isn't available.
include(":core")
if (!providers.gradleProperty("coreOnly").isPresent) {
    include(":app")
}
