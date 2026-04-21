import io.github.smyrgeorge.freepath.rust.RustBuildTask
import io.github.smyrgeorge.freepath.rust.RustGenerateDefFileTask
import io.github.smyrgeorge.freepath.rust.RustInteropExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.konan.target.Family
import org.jetbrains.kotlin.konan.target.KonanTarget

val rustInterop = extensions.create("rustInterop", RustInteropExtension::class.java)

afterEvaluate {
    val crateName = rustInterop.crateName.ifEmpty {
        error("rustInterop.crateName must be set in $path")
    }

    val kmp = extensions.findByType(KotlinMultiplatformExtension::class.java)
        ?: error("$path must apply Kotlin Multiplatform before io.github.smyrgeorge.freepath.rust.interop")

    kmp.targets
        .filterIsInstance<KotlinNativeTarget>()
        .filter { it.konanTarget.family == Family.IOS }
        .forEach { target -> configureRustTarget(target, rustInterop, crateName) }
}

fun Project.configureRustTarget(
    target: KotlinNativeTarget,
    extension: RustInteropExtension,
    crateName: String,
) {
    val rustTriple = iosRustTarget(target.konanTarget) ?: return
    val cargoDir = layout.projectDirectory.dir(extension.cargoDir)
    val rustOutDir = cargoDir.dir("target/$rustTriple/release")
    val templateDef = extension.templateDefFile ?: "src/nativeInterop/cinterop/${crateName}.def"
    val headerDirPath = layout.projectDirectory.dir(extension.headerDir).asFile.absolutePath

    val buildRustTask = tasks.register<RustBuildTask>(
        "buildRustIos_${target.konanTarget.name}",
    ) {
        this.cargoDir.set(cargoDir)
        rustTarget.set(rustTriple)
        outputDir.set(rustOutDir)
        sources.from(rustSourceTree(cargoDir.asFile))
    }

    val generateDefTask = tasks.register<RustGenerateDefFileTask>(
        "generateRustInteropDef_${crateName}_${target.konanTarget.name}",
    ) {
        templateDefFile.set(layout.projectDirectory.file(templateDef))
        this.crateName.set(crateName)
        libraryPath.set(rustOutDir.asFile.absolutePath)
        headerDir.set(headerDirPath)
        linkerOpts.set(extension.linkerOpts)
        staticLibraryFile.set(rustOutDir.file("lib${crateName}.a"))
        defFile.set(
            layout.buildDirectory.file(
                "generated-def/${target.konanTarget.name}/${crateName}.def"
            )
        )
        dependsOn(buildRustTask)
    }

    target.compilations.getByName("main").cinterops
        .create(crateName)
        .definitionFile.set(generateDefTask.flatMap { it.defFile })
}

fun iosRustTarget(konanTarget: KonanTarget): String? = when (konanTarget) {
    KonanTarget.IOS_X64 -> "x86_64-apple-ios"
    KonanTarget.IOS_ARM64 -> "aarch64-apple-ios"
    KonanTarget.IOS_SIMULATOR_ARM64 -> "aarch64-apple-ios-sim"
    else -> null
}

/**
 * Files whose changes should trigger a Rust rebuild. Must exclude `target/` — that directory
 * lives inside cargoDir and is the task's output, so including it would create an
 * input/output overlap error in Gradle.
 */
fun Project.rustSourceTree(cargoDir: File) = fileTree(cargoDir) {
    include("Cargo.toml", "Cargo.lock", "build.rs", "cbindgen.toml")
    include("src/**")
}
