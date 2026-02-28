import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
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
        namespace = "io.github.smyrgeorge.freepath.transport.lan"
        compileSdk = 36
        minSdk = 26
    }
    applyDefaultHierarchyTemplate()

    sourceSets {
        configureEach {
            languageSettings.progressiveMode = true
        }
        commonMain {
            dependencies {
                api(project(":freepath-transport"))
                implementation(libs.log4k)
                implementation(libs.ktor.network)
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
                implementation(libs.jmdns)
            }
        }
    }
}
