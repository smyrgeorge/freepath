import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.konan.target.KonanTarget
import java.io.File

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
    iosX64 { linkCryptoBridge() }
    iosArm64 { linkCryptoBridge() }
    iosSimulatorArm64 { linkCryptoBridge() }
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

// Link the Swift CryptoBridge static library built by :freepath-transport-crypto.
// The library is compiled by that module's compileSwiftBridge_<target> task; we
// reference its output directory here so any binary in this module can link it.
fun KotlinNativeTarget.linkCryptoBridge() {
    val target = this
    val sdk = when (konanTarget) {
        KonanTarget.IOS_X64, KonanTarget.IOS_SIMULATOR_ARM64 -> "iphonesimulator"
        KonanTarget.IOS_ARM64 -> "iphoneos"
        else -> return
    }
    val cryptoProject = project.project(":freepath-transport-crypto")
    val swiftLibDir = cryptoProject.layout.buildDirectory.dir("swift-bridge/${konanTarget.name}")
    val swiftToolchainLibDir = project.providers.exec {
        commandLine("xcrun", "--find", "swiftc")
    }.standardOutput.asText.map { path ->
        "${File(path.trim()).parentFile.parentFile.absolutePath}/lib/swift/$sdk"
    }
    binaries.all {
        linkerOpts(
            "-L${swiftLibDir.get().asFile.absolutePath}",
            "-lCryptoBridge",
            "-L${swiftToolchainLibDir.get()}",
            "-framework", "CryptoKit",
        )
        linkTaskProvider.configure {
            dependsOn(cryptoProject.tasks.named("compileSwiftBridge_${target.konanTarget.name}"))
        }
    }
}
