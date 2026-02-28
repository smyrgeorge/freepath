group = "io.github.smyrgeorge"
version = "0.0.1"

plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.pubhish) apply false
    alias(libs.plugins.dokka) apply false
    alias(libs.plugins.android) apply false
}

repositories {
    google()
    mavenCentral()
}

subprojects {
    group = rootProject.group
    version = rootProject.version

    repositories {
        google()
        mavenCentral()
        // IMPORTANT: must be last.
        mavenLocal()
    }
}
