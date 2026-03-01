import io.github.smyrgeorge.freepath.swift.BuildSwiftPackageTask
import io.github.smyrgeorge.freepath.swift.GenerateDefFileTask
import io.github.smyrgeorge.freepath.swift.SwiftInteropExtension
import org.gradle.kotlin.dsl.register
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.konan.target.Family
import org.jetbrains.kotlin.konan.target.KonanTarget
import java.io.File

val swiftInterop = extensions.create("swiftInterop", SwiftInteropExtension::class.java)

afterEvaluate {
    val packageName = swiftInterop.packageName.ifEmpty {
        error("swiftInterop.packageName must be set in $path")
    }

    val kmp = extensions.findByType(KotlinMultiplatformExtension::class.java)
        ?: error("$path must apply the Kotlin Multiplatform plugin before io.github.smyrgeorge.freepath.swift.interop")

    kmp.targets
        .filterIsInstance<KotlinNativeTarget>()
        .filter { it.konanTarget.family == Family.IOS }
        .forEach { target -> configureSwiftTarget(target, swiftInterop, packageName) }
}

fun Project.configureSwiftTarget(
    target: KotlinNativeTarget,
    extension: SwiftInteropExtension,
    packageName: String,
) {
    val (sdkName, swiftTriple) = iosSwiftTarget(target.konanTarget) ?: return

    val toolchainLibDir = providers.exec {
        commandLine("xcrun", "--find", "swiftc")
    }.standardOutput.asText.map { swiftcPath ->
        "${File(swiftcPath.trim()).parentFile.parentFile.absolutePath}/lib/swift/$sdkName"
    }

    val pkgDir = layout.projectDirectory.dir(extension.swiftSourceDir)
    val spmOutDir = layout.buildDirectory.dir("swift-spm/${target.konanTarget.name}")

    val spmLibDir = providers.exec {
        commandLine(
            "swift", "build",
            "--package-path", pkgDir.asFile.absolutePath,
            "--configuration", "release",
            "--build-path", spmOutDir.get().asFile.absolutePath,
            "--show-bin-path",
        )
    }.standardOutput.asText.map { it.trim() }

    val templateDef = extension.templateDefFile ?: "src/nativeInterop/cinterop/${packageName}.def"

    val computedLinkerOpts = toolchainLibDir.map { toolchain ->
        buildString {
            append("-L$toolchain")
            for (fw in extension.frameworks) append(" -framework $fw")
        }
    }

    val buildSwiftTask = tasks.register<BuildSwiftPackageTask>(
        "buildSwiftPackage_${target.konanTarget.name}",
    ) {
        swiftPackageDir.set(pkgDir)
        outputDir.set(spmOutDir)
        sdk.set(sdkName)
        swiftTarget.set(swiftTriple)
    }

    val generateDefTask = tasks.register<GenerateDefFileTask>(
        "generateSwiftInteropDef_${packageName}_${target.konanTarget.name}",
    ) {
        templateDefFile.set(layout.projectDirectory.file(templateDef))
        libraryPath.set(spmLibDir)
        linkerOpts.set(computedLinkerOpts)
        defFile.set(layout.buildDirectory.file("generated-def/${target.konanTarget.name}/${packageName}.def"))
        dependsOn(buildSwiftTask)
    }

    target.compilations.getByName("main").cinterops
        .create(packageName)
        .definitionFile.set(generateDefTask.flatMap { it.defFile })
}

fun iosSwiftTarget(konanTarget: KonanTarget): Pair<String, String>? = when (konanTarget) {
    KonanTarget.IOS_X64 -> "iphonesimulator" to "x86_64-apple-ios14.0-simulator"
    KonanTarget.IOS_ARM64 -> "iphoneos" to "arm64-apple-ios14.0"
    KonanTarget.IOS_SIMULATOR_ARM64 -> "iphonesimulator" to "arm64-apple-ios14.0-simulator"
    else -> null
}
