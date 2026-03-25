// freepath-libp2p/build.gradle.kts
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android)
    id("io.github.smyrgeorge.freepath.rust.interop")
}

rustInterop {
    crateName = "freepath_libp2p"
    // cargoDir = "src/rust"        // default
    // headerDir = "src/nativeInterop/cinterop"  // default
    // if_watch (network interface monitoring) requires SystemConfiguration;
    // ring (crypto) requires Security for SecRandomCopyBytes on Darwin.
    linkerOpts = "-framework SystemConfiguration -framework Security"
}

kotlin {
    jvm {
        compilerOptions {
            freeCompilerArgs.set(listOf("-Xjsr305=strict"))
            jvmTarget.set(JvmTarget.JVM_21)
        }
    }
    android {
        namespace = "io.github.smyrgeorge.freepath.libp2p"
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
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.log4k)
            }
        }
        commonTest {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.core)
            }
        }
        jvmMain {
            resources.srcDir(layout.buildDirectory.dir("generated/resources/jvmAndroid"))
            dependencies {
                implementation(libs.jmdns)
            }
        }
        androidMain {
            dependencies {
                implementation(project(":freepath-util"))
            }
        }
    }
}

// ── Build Rust for Android (requires cargo-ndk + NDK installed via SDK Manager) ─
val jniLibsDir = layout.projectDirectory.dir("src/androidMain/jniLibs")
val rustDir = layout.projectDirectory.dir("src/rust")
val cargo: String = file("${System.getProperty("user.home")}/.cargo/bin/cargo")
    .takeIf { it.exists() }?.absolutePath ?: "cargo"

val buildRustAndroid = tasks.register<Exec>("buildRustAndroid") {
    workingDir(rustDir)
    commandLine(
        cargo, "ndk",
        "-t", "arm64-v8a",
        "-t", "x86_64",
        "-o", jniLibsDir.asFile.absolutePath,
        "build", "--release",
    )
    outputs.upToDateWhen { false } // cargo handles its own incrementalism
}

afterEvaluate {
    // Wire buildRustAndroid before any task that merges JNI libraries into the APK/AAR.
    tasks.matching { it.name.contains("JniLib", ignoreCase = true) || it.name.contains("MergeJni", ignoreCase = true) }
        .configureEach { dependsOn(buildRustAndroid) }
}

// ── Build Rust for JVM host (macOS or Linux) and copy to JVM resources ────────
val hostTarget = if (System.getProperty("os.name").lowercase().contains("mac")) {
    if (System.getProperty("os.arch") == "aarch64") "aarch64-apple-darwin" else "x86_64-apple-darwin"
} else "x86_64-unknown-linux-gnu"
val hostLibExt = if (hostTarget.contains("darwin")) "dylib" else "so"
val hostLib = rustDir.file("target/$hostTarget/release/libfreepath_libp2p.$hostLibExt")
// Name that LibP2pNativeLoader looks for on the JVM classpath
val hostLibResourceName = when {
    hostTarget == "aarch64-apple-darwin" -> "libfreepath_libp2p_aarch64.dylib"
    hostTarget.contains("darwin") -> "libfreepath_libp2p.dylib"
    else -> "libfreepath_libp2p.so"
}

val buildRustJvm = tasks.register<Exec>("buildRustJvm") {
    workingDir(rustDir)
    commandLine(cargo, "build", "--release", "--target", hostTarget)
    outputs.file(hostLib)
    outputs.upToDateWhen { false } // cargo handles its own incrementalism
}

val copyNativeLibJvm = tasks.register<Copy>("copyNativeLibJvm") {
    from(hostLib)
    into(layout.buildDirectory.dir("generated/resources/jvmAndroid"))
    rename(".+", hostLibResourceName)
    dependsOn(buildRustJvm)
}

tasks.matching { it.name.startsWith("process") && (it.name.endsWith("Resources") || it.name.endsWith("JavaRes")) }
    .configureEach { dependsOn(copyNativeLibJvm) }

tasks.named("jvmProcessResources") { dependsOn(copyNativeLibJvm) }

tasks.named<Test>("jvmTest") {
    dependsOn(copyNativeLibJvm)
    // Put the native lib on java.library.path so System.load can find it.
    systemProperty(
        "freepath.libp2p.native.path",
        layout.buildDirectory.dir("generated/resources/jvmAndroid").get().asFile.absolutePath
    )
}
