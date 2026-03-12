// build-logic/src/main/kotlin/io/github/smyrgeorge/freepath/rust/RustGenerateDefFileTask.kt
package io.github.smyrgeorge.freepath.rust

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import java.security.MessageDigest

/**
 * Generates a Kotlin/Native cinterop `.def` file for a Rust static library.
 *
 * Unlike [io.github.smyrgeorge.freepath.swift.GenerateDefFileTask], this task:
 * - Accepts an optional [linkerOpts] (Rust static libs typically need none)
 * - Derives `staticLibraries` from the crate name, not the `.def` filename
 * - Injects `compilerOpts` pointing to the directory containing the cbindgen header
 *
 * Injects before the `---` separator:
 *   staticLibraries = lib<crateName>.a
 *   libraryPaths = <libraryPath>
 *   compilerOpts = -I<headerDir>
 *   linkerOpts = <linkerOpts>  (only if non-empty)
 */
abstract class RustGenerateDefFileTask : DefaultTask() {

    /** Static template: contains `headers`, `package`, `---`, and nothing after it. */
    @get:InputFile
    abstract val templateDefFile: RegularFileProperty

    /** Rust crate name as it appears in the output library, e.g. `freepath_libp2p`. */
    @get:Input
    abstract val crateName: Property<String>

    /** Absolute path to the directory containing `lib<crateName>.a` for this target. */
    @get:Input
    abstract val libraryPath: Property<String>

    /** Absolute path to the directory containing the cbindgen-generated `.h` file. */
    @get:Input
    abstract val headerDir: Property<String>

    /** Additional linker opts (e.g. `-framework Security`). Empty string = omitted. */
    @get:Input
    @get:Optional
    abstract val linkerOpts: Property<String>

    /**
     * The actual static library file (e.g. `libfreepath_libp2p.a`).
     * Tracked as an input so that when the Rust build produces a new binary the def file
     * content changes (via an embedded hash), which invalidates the downstream cinterop task.
     */
    @get:InputFile
    @get:Optional
    abstract val staticLibraryFile: RegularFileProperty

    @get:OutputFile
    abstract val defFile: RegularFileProperty

    @TaskAction
    fun generate() {
        val template = templateDefFile.get().asFile.readText()
        val separatorIndex = template.indexOf("---")
        check(separatorIndex >= 0) { "Template .def must contain a '---' separator" }

        val header = template.substring(0, separatorIndex).trimEnd()
        val body = template.substring(separatorIndex + 3)

        // Embed a hash of the static library so this file's content changes when the lib
        // is rebuilt, invalidating the Kotlin/Native cinterop cache.
        val libHash = staticLibraryFile.orNull?.asFile
            ?.takeIf { it.exists() }
            ?.let { f ->
                MessageDigest.getInstance("SHA-256").digest(f.readBytes())
                    .joinToString("") { "%02x".format(it) }
            } ?: "unknown"

        val out = defFile.get().asFile
        out.parentFile.mkdirs()
        out.writeText(buildString {
            appendLine(header)
            appendLine("staticLibraries = lib${crateName.get()}.a")
            appendLine("libraryPaths = ${libraryPath.get()}")
            appendLine("compilerOpts = -I${headerDir.get()}")
            val opts = linkerOpts.orNull?.takeIf { it.isNotBlank() }
            if (opts != null) appendLine("linkerOpts = $opts")
            appendLine("# lib-sha256: $libHash")
            append("---")
            append(body)
        })
    }
}
