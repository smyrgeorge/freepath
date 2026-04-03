import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeHotReload)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvm()
    android {
        namespace = "io.github.smyrgeorge.freepath.shared"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        androidResources {
            enable = true
        }
    }

    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
    }
    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.ktor.client.core)
            implementation(project(":freepath-contact"))
            implementation(project(":freepath-crypto"))
            implementation(project(":freepath-content"))
            implementation(project(":freepath-database"))
            implementation(project(":freepath-libble"))
            implementation(project(":freepath-libp2p"))
            implementation(project(":freepath-libnet"))
            implementation(project(":freepath-util"))

            implementation(libs.log4k)
            implementation(libs.qrose)
            implementation(libs.actor4k)
            implementation(libs.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.ui.tooling.preview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.kotlinx.serialization.protobuf)
            implementation(libs.markdown.renderer)
            implementation(libs.markdown.renderer.m3)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        androidMain.dependencies {
            implementation(libs.ktor.client.cio)
            implementation(libs.log4k.slf4j)
            implementation(libs.androidx.activity.compose)
            // Preview tooling - only needed at compile time
            compileOnly(libs.androidx.ui.tooling)
            compileOnly(libs.androidx.emoji2)
            compileOnly(libs.androidx.customview.poolingcontainer)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
        jvmMain.dependencies {
            implementation(libs.ktor.client.cio)
            implementation(libs.log4k.slf4j)
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutines.swing)
        }
    }
}

compose.desktop {
    application {
        mainClass = "io.github.smyrgeorge.freepath.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "io.github.smyrgeorge.freepath"
            packageVersion = "1.0.0"
        }
    }
}
