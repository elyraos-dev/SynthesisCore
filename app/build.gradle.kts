plugins {
    alias(libs.plugins.android.application)
}

fun gitRevisionCount(): Int {
    return try {
        val process = ProcessBuilder("git", "rev-list", "--count", "HEAD")
            .redirectErrorStream(true)
            .start()
        process.inputStream.bufferedReader().readText().trim().toInt()
    } catch (_: Exception) {
        System.err.println("ERROR: Failed to get git revision count")
        1
    }
}

android {
    namespace = "com.febricahyaa.synthesiscore"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.febricahyaa.synthesiscore"
        minSdk = 28
        targetSdk = 36
        versionCode = gitRevisionCount()
        versionName = "1.0.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isCrunchPngs = true

            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }

        debug {
            isMinifyEnabled = false
            isDebuggable = true
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    // The telemetry contract, encoder, thermal sampling policy and atomic writer.
    // Pure JVM, no Android APIs — and therefore host-testable via `:telemetry:test`.
    implementation(project(":telemetry"))
    implementation(libs.hiddenapibypass)

    testImplementation(libs.junit)
}
