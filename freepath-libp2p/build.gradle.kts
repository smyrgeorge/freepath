import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android)
    id("io.github.smyrgeorge.freepath.rust.interop")
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
                implementation(project(":freepath-util"))
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

rustInterop {
    crateName = "freepath_libp2p"
    // cargoDir = "src/rust"        // default
    // headerDir = "src/nativeInterop/cinterop"  // default
    // if_watch (network interface monitoring) requires SystemConfiguration;
    // ring (crypto) requires Security for SecRandomCopyBytes on Darwin.
    linkerOpts = "-framework SystemConfiguration -framework Security"
}

// ── Build Rust for Android (requires cargo-ndk + NDK installed via SDK Manager) ─
val jniLibsDir = layout.projectDirectory.dir("src/androidMain/jniLibs")
val rustDir = layout.projectDirectory.dir("src/rust")
val cargo: String = file("${System.getProperty("user.home")}/.cargo/bin/cargo")
    .takeIf { it.exists() }?.absolutePath ?: "cargo"

// Files whose changes should trigger a Rust rebuild. Excludes `target/` (the cargo output
// directory, which lives inside rustDir) to avoid input/output overlap errors in Gradle.
val rustSources = fileTree(rustDir) {
    include("Cargo.toml", "Cargo.lock", "build.rs", "cbindgen.toml")
    include("src/**")
}

val buildRustAndroid = tasks.register<Exec>("buildRustAndroid") {
    workingDir(rustDir)
    inputs.files(rustSources).withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.dir(jniLibsDir)
    commandLine(
        cargo, "ndk",
        "-t", "arm64-v8a",
        "-t", "x86_64",
        "-o", jniLibsDir.asFile.absolutePath,
        "build", "--release",
    )
}

afterEvaluate {
    // Wire buildRustAndroid before any task that merges JNI libraries into the APK/AAR.
    tasks.matching {
        it.name.contains("JniLib", ignoreCase = true)
                || it.name.contains("MergeJni", ignoreCase = true)
    }.configureEach { dependsOn(buildRustAndroid) }
}

// ── Build Rust for JVM host (macOS, Linux, Windows) and copy to JVM resources ─
val hostOs: String = System.getProperty("os.name").lowercase()
val hostArch: String = System.getProperty("os.arch").lowercase()
val isMacHost: Boolean = hostOs.contains("mac") || hostOs.contains("darwin")
val isLinuxHost: Boolean = hostOs.contains("linux")
val isWindowsHost: Boolean = hostOs.contains("windows")
val isArm64Host: Boolean = hostArch == "aarch64" || hostArch == "arm64"

val hostTarget: String = when {
    isMacHost && isArm64Host -> "aarch64-apple-darwin"
    isMacHost -> "x86_64-apple-darwin"
    isLinuxHost && isArm64Host -> "aarch64-unknown-linux-gnu"
    isLinuxHost -> "x86_64-unknown-linux-gnu"
    isWindowsHost && isArm64Host -> "aarch64-pc-windows-msvc"
    isWindowsHost -> "x86_64-pc-windows-msvc"
    else -> error("Unsupported host for buildRustJvm: os=$hostOs arch=$hostArch")
}

// Rust output: Unix prefixes with `lib`, Windows does not; extension is platform-specific.
val hostLibPrefix: String = if (isWindowsHost) "" else "lib"
val hostLibExt: String = when {
    isMacHost -> "dylib"
    isWindowsHost -> "dll"
    else -> "so"
}
val hostLib = rustDir.file("target/$hostTarget/release/${hostLibPrefix}freepath_libp2p.$hostLibExt")

// Name the JVM loader looks for on the classpath. Arm64 variants carry an `_aarch64`
// suffix so a fat JAR can distinguish arm64 from x86_64 within the same OS.
val hostLibResourceName: String = when (hostTarget) {
    "aarch64-apple-darwin" -> "libfreepath_libp2p_aarch64.dylib"
    "x86_64-apple-darwin" -> "libfreepath_libp2p.dylib"
    "aarch64-unknown-linux-gnu" -> "libfreepath_libp2p_aarch64.so"
    "x86_64-unknown-linux-gnu" -> "libfreepath_libp2p.so"
    "aarch64-pc-windows-msvc" -> "freepath_libp2p_aarch64.dll"
    "x86_64-pc-windows-msvc" -> "freepath_libp2p.dll"
    else -> error("Unsupported hostTarget: $hostTarget")
}

val buildRustJvm = tasks.register<Exec>("buildRustJvm") {
    workingDir(rustDir)
    inputs.files(rustSources).withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.file(hostLib)
    commandLine(cargo, "build", "--release", "--target", hostTarget)
}

val copyNativeLibJvm = tasks.register<Copy>("copyNativeLibJvm") {
    from(hostLib)
    into(layout.buildDirectory.dir("generated/resources/jvmAndroid"))
    rename(".+", hostLibResourceName)
    dependsOn(buildRustJvm)
}

tasks.matching {
    it.name.startsWith("process")
            && (it.name.endsWith("Resources") || it.name.endsWith("JavaRes"))
}.configureEach { dependsOn(copyNativeLibJvm) }

tasks.named("jvmProcessResources") { dependsOn(copyNativeLibJvm) }

tasks.named<Test>("jvmTest") {
    dependsOn(copyNativeLibJvm)
    // Put the native lib on java.library.path so System.load can find it.
    systemProperty(
        "freepath.libp2p.native.path",
        layout.buildDirectory.dir("generated/resources/jvmAndroid").get().asFile.absolutePath
    )
}
