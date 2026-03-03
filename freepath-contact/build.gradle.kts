import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android)
    alias(libs.plugins.ksp)
}

kotlin {
    jvm {
        compilerOptions {
            freeCompilerArgs.set(listOf("-Xjsr305=strict"))
            jvmTarget.set(JvmTarget.JVM_21)
        }
    }
    android {
        namespace = "io.github.smyrgeorge.freepath.contact"
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
                implementation(project(":freepath-crypto"))
                implementation(project(":freepath-util"))
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.sqlx4k)
            }
            // Config if your code is under the commonMain module.
            kotlin.srcDir("build/generated/ksp/metadata/commonMain/kotlin")
        }
        commonTest {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }
    }
}

ksp {
    arg("dialect", "sqlite")
    arg("output-package", "io.github.smyrgeorge.freepath.contact.generated")
}

// Config if your code is under the commonMain module.
dependencies {
    add("kspCommonMainMetadata", libs.sqlx4k.codegen)
}

tasks.withType<KotlinCompilationTask<*>> {
    dependsOn("kspCommonMainKotlinMetadata")
}
