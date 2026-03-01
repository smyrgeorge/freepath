import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android)
}

kotlin {
    jvm {
        compilerOptions {
            freeCompilerArgs.set(listOf("-Xjsr305=strict"))
            jvmTarget.set(JvmTarget.JVM_21)
        }
    }
    android {
        namespace = "io.github.smyrgeorge.freepath.transport"
        compileSdk = 36
        minSdk = 26
    }
    iosX64()
    iosArm64()
    iosSimulatorArm64()
    applyDefaultHierarchyTemplate()

    sourceSets {
        configureEach {
            languageSettings.progressiveMode = true
        }
        commonMain {
            dependencies {
                api(project(":freepath-transport-crypto"))
                implementation(libs.log4k)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.bignum)
            }
        }
        commonTest {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }
        jvmMain {
            dependencies {
                implementation(libs.log4k.slf4j)
            }
        }
    }
}
