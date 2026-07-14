/*
 * Pure-JVM telemetry contract.
 *
 * This module deliberately has no Android dependency. Everything that decides *what*
 * SynthesisCore emits — the schema, the encoder, thermal sampling policy, atomic
 * replacement, provider isolation — lives here so it can be tested on a host JVM with
 * `./gradlew :telemetry:test`, with no emulator, no device, and no Android SDK.
 *
 * The `:app` module supplies the Android-specific half: implementations of the provider
 * interfaces that reach into PowerManager, ActivityTaskManager and friends.
 */
plugins {
    // Applied without a version on purpose. AGP 9 already contributes the Kotlin Gradle
    // Plugin to the shared build classpath, so pinning a second version here fails with
    // "already on the classpath with an unknown version". Inheriting it keeps exactly one
    // Kotlin toolchain in the build.
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        // The Android side is compiled against Java 11 bytecode; keep this module loadable there.
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        allWarningsAsErrors.set(true)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

dependencies {
    testImplementation(libs.junit)
}

tasks.withType<Test>().configureEach {
    useJUnit()
    testLogging {
        events("passed", "skipped", "failed")
    }
}
