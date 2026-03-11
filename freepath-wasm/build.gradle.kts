import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android)
    id("io.github.smyrgeorge.freepath.swift.interop")
}

kotlin {
    jvm {
        compilerOptions {
            freeCompilerArgs.set(listOf("-Xjsr305=strict"))
            jvmTarget.set(JvmTarget.JVM_21)
        }
    }
    android {
        namespace = "io.github.smyrgeorge.freepath.wasm"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
    }
    iosX64()
    iosArm64()
    iosSimulatorArm64()
    applyDefaultHierarchyTemplate()

    sourceSets {
        configureEach { languageSettings.progressiveMode = true }
        commonMain { dependencies { } }
        commonTest { dependencies { implementation(libs.kotlin.test) } }
        val jvmAndroidMain by creating {
            dependsOn(commonMain.get())
            dependencies {
                implementation(libs.chicory.runtime)
            }
        }
        jvmMain { dependsOn(jvmAndroidMain) }
        androidMain { dependsOn(jvmAndroidMain) }
    }
}

swiftInterop {
    packageName = "Wasm3Bridge"
    cDependencyTargets = listOf("Cwasm3")
    cDefines = listOf("d_m3MaxFunctionStackHeight=2000", "d_m3VerboseErrorMessages")
}

// ── Build Rust test fixtures (cargo → wasm32-unknown-unknown) ─────────────────
val testFixturesDir = layout.projectDirectory.dir("src/test-fixtures")
val testFixturesWasm = testFixturesDir.file(
    "target/wasm32-unknown-unknown/release/freepath_wasm_test_fixtures.wasm"
)

val buildTestFixtures = tasks.register<Exec>("buildTestFixtures") {
    workingDir(testFixturesDir)
    commandLine("cargo", "build", "--release", "--target", "wasm32-unknown-unknown")
    outputs.file(testFixturesWasm)
}

// ── Pass wasm path to tests via property / env var ───────────────────────────
val wasmPath: String = testFixturesWasm.asFile.absolutePath

tasks.named<Test>("jvmTest") {
    dependsOn(buildTestFixtures)
    systemProperty("test.fixtures.wasm", wasmPath)
}

// ── iOS tests: copy wasm next to the test binary ─────────────────────────────
// KotlinNativeSimulatorTest runs via xcrun simctl spawn which does not forward
// environment variables from the Gradle process. Copying the file next to the
// binary and reading it via NSBundle.mainBundle.bundlePath is the reliable path.
listOf("iosSimulatorArm64", "iosX64", "iosArm64").forEach { targetName ->
    val copyTask = tasks.register<Copy>("copyTestWasm_$targetName") {
        from(testFixturesWasm)
        rename { "echo.wasm" }
        into(layout.buildDirectory.dir("bin/$targetName/debugTest"))
        dependsOn(buildTestFixtures)
        dependsOn("linkDebugTest${targetName.replaceFirstChar { it.uppercaseChar() }}")
    }
    tasks.matching { it.name == "${targetName}Test" }.configureEach {
        dependsOn(copyTask)
    }
}
