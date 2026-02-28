import org.gradle.process.ExecOperations
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.konan.target.KonanTarget
import java.io.ByteArrayOutputStream
import javax.inject.Inject

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
        namespace = "io.github.smyrgeorge.freepath.transport.crypto"
        compileSdk = 36
        minSdk = 26
    }
    iosX64 { cryptoBridgeCinterop() }
    iosArm64 { cryptoBridgeCinterop() }
    iosSimulatorArm64 { cryptoBridgeCinterop() }
    applyDefaultHierarchyTemplate()

    sourceSets {
        configureEach {
            languageSettings.progressiveMode = true
        }
        commonTest {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }
        val jvmAndroidMain by creating {
            dependsOn(commonMain.get())
            dependencies {
                implementation(libs.bouncycastle)
            }
        }
        jvmMain {
            dependsOn(jvmAndroidMain)
        }
        androidMain {
            dependsOn(jvmAndroidMain)
        }
    }
}

// Compiles CryptoBridge.swift into a static library for a given iOS target.
// This is necessary because the Swift file is not compiled by Kotlin/Native's
// cinterop — only the ObjC header in the .def file is used for bindings. The
// actual implementation must be compiled separately and linked in.
abstract class CompileSwiftBridgeTask @Inject constructor(
    private val execOps: ExecOperations,
) : DefaultTask() {
    @get:InputFile
    abstract val swiftSource: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Input
    abstract val sdk: Property<String>

    @get:Input
    abstract val swiftTarget: Property<String>

    @TaskAction
    fun compile() {
        val outDir = outputDir.get().asFile
        outDir.mkdirs()

        val sdkPath = ByteArrayOutputStream().also { out ->
            execOps.exec {
                commandLine("xcrun", "--sdk", sdk.get(), "--show-sdk-path")
                standardOutput = out
            }
        }.toString().trim()

        val objFile = File(outDir, "CryptoBridge.o")
        execOps.exec {
            commandLine(
                "swiftc",
                "-target", swiftTarget.get(),
                "-sdk", sdkPath,
                "-parse-as-library",
                "-module-name", "CryptoBridge",
                "-emit-object",
                "-o", objFile.absolutePath,
                swiftSource.get().asFile.absolutePath,
            )
        }

        execOps.exec {
            commandLine("ar", "rcs", File(outDir, "libCryptoBridge.a").absolutePath, objFile.absolutePath)
        }
    }
}

fun KotlinNativeTarget.cryptoBridgeCinterop() {
    val (sdk, swiftTarget) = when (konanTarget) {
        KonanTarget.IOS_X64 -> "iphonesimulator" to "x86_64-apple-ios14.0-simulator"
        KonanTarget.IOS_ARM64 -> "iphoneos" to "arm64-apple-ios14.0"
        KonanTarget.IOS_SIMULATOR_ARM64 -> "iphonesimulator" to "arm64-apple-ios14.0-simulator"
        else -> error("Unsupported target: $konanTarget")
    }

    // Swift toolchain lib dir — needed for Swift compatibility stubs auto-linked by ld.
    // Path: <toolchain>/usr/lib/swift/<sdk>  (derived from `xcrun --find swiftc`).
    val swiftToolchainLibDir = project.providers.exec {
        commandLine("xcrun", "--find", "swiftc")
    }.standardOutput.asText.map { swiftcPath ->
        "${File(swiftcPath.trim()).parentFile.parentFile.absolutePath}/lib/swift/$sdk"
    }

    val swiftOutDir = project.layout.buildDirectory.dir("swift-bridge/${konanTarget.name}")

    val compileSwiftTask = project.tasks.register<CompileSwiftBridgeTask>(
        "compileSwiftBridge_${konanTarget.name}",
    ) {
        swiftSource.set(project.file("src/iosMain/swift/CryptoBridge.swift"))
        outputDir.set(swiftOutDir)
        this.sdk.set(sdk)
        this.swiftTarget.set(swiftTarget)
    }

    compilations.getByName("main") {
        cinterops.create("CryptoBridge")
    }

    binaries.all {
        linkerOpts(
            "-L${swiftOutDir.get().asFile.absolutePath}",
            "-lCryptoBridge",
            "-L${swiftToolchainLibDir.get()}",
            "-framework", "CryptoKit",
        )
        linkTaskProvider.configure {
            dependsOn(compileSwiftTask)
        }
    }
}
