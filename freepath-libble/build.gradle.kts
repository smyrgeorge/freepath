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
        namespace = "io.github.smyrgeorge.freepath.libble"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
    }
    iosX64()
    iosArm64()
    iosSimulatorArm64()
    applyDefaultHierarchyTemplate()

    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    sourceSets {
        configureEach { languageSettings.progressiveMode = true }
        commonMain {
            dependencies {
                implementation(project(":freepath-contact"))
                implementation(project(":freepath-crypto"))
                implementation(libs.kable.core)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.log4k)
            }
        }
        commonTest {
            dependencies {
                implementation(project(":freepath-crypto"))
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
        androidMain {
            dependencies {
                implementation(project(":freepath-util"))
            }
        }
    }
}
