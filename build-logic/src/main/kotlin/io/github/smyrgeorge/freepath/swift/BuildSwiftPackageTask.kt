package io.github.smyrgeorge.freepath.swift

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import java.io.File
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
 *
 * Optionally compiles vendored C source directories (e.g. `Sources/Cwasm3`) and merges
 * the resulting objects into the final static library so that the cinterop consumers
 * receive a single self-contained `.a`.
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

    /**
     * Optional list of C source target names under `Sources/` that should be compiled
     * and merged into the final static library.  Each entry is expected to have its
     * public headers in a sub-directory named `include/` (e.g. `Sources/Cwasm3/include/`).
     *
     * When non-empty the task:
     *   1. Compiles every `.c` file under `Sources/<cTarget>/` with `clang`.
     *   2. Passes `-I Sources/<cTarget>/include` to `swiftc` so the module map is found.
     *   3. Merges all objects (Swift + C) into `lib<PackageName>.a`.
     */
    @get:Input
    @get:Optional
    abstract val cDependencyTargets: ListProperty<String>

    /**
     * Optional list of `-D<DEFINE>` strings forwarded to `clang` when compiling
     * the C dependency targets listed in [cDependencyTargets].
     */
    @get:Input
    @get:Optional
    abstract val cDefines: ListProperty<String>

    @TaskAction
    fun build() {
        val sdkPath = xcrun("--sdk", sdk.get(), "--show-sdk-path")
        val swiftcPath = xcrun("--find", "swiftc")
        val clangPath = xcrun("--find", "clang")
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

        // ── Compile C dependencies ──────────────────────────────────────────
        val cObjectFiles = mutableListOf<File>()
        val cIncludePaths = mutableListOf<String>()

        for (cTarget in cDependencyTargets.getOrElse(emptyList())) {
            val cSrcDir = swiftPackageDir.get().asFile.resolve("Sources/$cTarget")
            val cIncludeDir = cSrcDir.resolve("include")
            if (cIncludeDir.exists()) cIncludePaths.add(cIncludeDir.absolutePath)

            val cObjDir = releaseDir.resolve("c-objects-$cTarget").also { it.mkdirs() }
            val cFiles = cSrcDir.walkTopDown()
                .filter { it.isFile && it.extension == "c" }
                .sorted()
                .toList()

            for (cFile in cFiles) {
                val objFile = cObjDir.resolve("${cFile.nameWithoutExtension}.o")
                execOps.exec {
                    commandLine(buildList {
                        add(clangPath)
                        add("-target"); add(swiftTarget.get())
                        add("-isysroot"); add(sdkPath)
                        for (inc in cIncludePaths) { add("-I"); add(inc) }
                        for (def in cDefines.getOrElse(emptyList())) add("-D$def")
                        add("-O2")
                        add("-c"); add(cFile.absolutePath)
                        add("-o"); add(objFile.absolutePath)
                    })
                }
                cObjectFiles.add(objFile)
            }
        }

        // ── Compile Swift sources ───────────────────────────────────────────
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
                // Make the C module maps visible to swiftc
                for (inc in cIncludePaths) { add("-I"); add(inc) }
                addAll(swiftFiles)
            })
        }

        // ── Archive Swift + C objects into a single static library ──────────
        execOps.exec {
            commandLine(buildList {
                add(libtoolPath)
                add("-static")
                add("-no_warning_for_no_symbols")
                add("-o"); add(libraryFile.absolutePath)
                add(objectFile.absolutePath)
                addAll(cObjectFiles.map { it.absolutePath })
            })
        }
    }

    private fun xcrun(vararg args: String): String = ByteArrayOutputStream().also { out ->
        execOps.exec {
            commandLine("xcrun", *args)
            standardOutput = out
        }
    }.toString().trim()
}
