package io.github.smyrgeorge.freepath.swift

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import javax.inject.Inject

/**
 * Compiles a Swift Package for a specific Apple target triple and archives it into
 * `lib<PackageName>.a` under `<outputDir>/release/`.
 *
 * Invokes `swiftc` and `libtool` directly instead of `swift build` to avoid the
 * "using sysroot for 'MacOSX' but targeting 'iPhone'" warning that SPM emits when
 * cross-compiling for iOS targets: SPM's manifest-compilation stage always runs against
 * the host (macOS) SDK and warns when the compiler target is a different platform.
 * Calling `swiftc` directly skips that stage entirely.
 */
abstract class BuildSwiftPackageTask @Inject constructor(
    private val execOps: ExecOperations,
) : DefaultTask() {

    @get:InputDirectory
    abstract val swiftPackageDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Input
    abstract val sdk: Property<String>

    @get:Input
    abstract val swiftTarget: Property<String>

    /** SPM target name; also used as the Swift module name and to derive the library filename. */
    @get:Input
    abstract val packageName: Property<String>

    @TaskAction
    fun build() {
        val sdkPath = xcrun("--sdk", sdk.get(), "--show-sdk-path")
        val swiftcPath = xcrun("--find", "swiftc")
        val libtoolPath = xcrun("--find", "libtool")

        val name = packageName.get()
        val sourcesDir = swiftPackageDir.get().asFile.resolve("Sources/$name")
        val swiftFiles = sourcesDir.walkTopDown()
            .filter { it.isFile && it.extension == "swift" }
            .map { it.absolutePath }
            .sorted()
            .toList()

        check(swiftFiles.isNotEmpty()) { "No Swift source files found in $sourcesDir" }

        val releaseDir = outputDir.get().asFile.resolve("release").also { it.mkdirs() }
        val objectFile = releaseDir.resolve("$name.o")
        val libraryFile = releaseDir.resolve("lib$name.a")

        // Compile all Swift sources in one whole-module invocation.
        execOps.exec {
            commandLine(buildList {
                add(swiftcPath)
                add("-target"); add(swiftTarget.get())
                add("-sdk"); add(sdkPath)
                add("-module-name"); add(name)
                add("-parse-as-library")
                add("-O")
                add("-emit-object")
                add("-o"); add(objectFile.absolutePath)
                addAll(swiftFiles)
            })
        }

        // Archive the object file into a static library.
        execOps.exec {
            commandLine(libtoolPath, "-static", "-o", libraryFile.absolutePath, objectFile.absolutePath)
        }
    }

    private fun xcrun(vararg args: String): String = ByteArrayOutputStream().also { out ->
        execOps.exec {
            commandLine("xcrun", *args)
            standardOutput = out
        }
    }.toString().trim()
}
