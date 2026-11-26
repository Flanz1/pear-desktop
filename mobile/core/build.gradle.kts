plugins {
    id("org.jetbrains.kotlin.jvm")
}

// Bytecode level 17 so the module works with the Android toolchain, built with
// whatever JDK (17+) runs Gradle.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    // kotlinx-serialization is used only for its JSON tree (JsonElement).
    // Validation is hand-written to match the zod schemas exactly.
    api(libs.kotlinx.serialization.json)
    api(libs.kotlinx.coroutines.core)
    api(libs.okhttp)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.bouncycastle.pkix)
}

tasks.test {
    useJUnitPlatform()
    // The contract test reads the shared fixtures in protocol/fixtures.
    val fixtures = rootProject.layout.projectDirectory.dir("../protocol/fixtures")
    inputs.dir(fixtures).withPropertyName("protocolFixtures")
    systemProperty("pear.fixtures", fixtures.asFile.absolutePath)
    // -Pinterop runs DesktopInteropTest against the real desktop listener
    // (needs Node 22+ and `pnpm install` in the repository root).
    systemProperty("pear.interop", providers.gradleProperty("interop").isPresent.toString())
    systemProperty("pear.repoRoot", rootProject.layout.projectDirectory.dir("..").asFile.absolutePath)
    if (providers.gradleProperty("interop").isPresent) outputs.upToDateWhen { false }
}
