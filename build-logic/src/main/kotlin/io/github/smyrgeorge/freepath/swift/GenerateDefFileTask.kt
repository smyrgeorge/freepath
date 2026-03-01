package io.github.smyrgeorge.freepath.swift

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

/**
 * Generates the final cinterop `.def` file by reading a static template that holds the
 * `language`, `package` declaration, and ObjC interface, then injecting the
 * build-time linker settings (`staticLibraries`, `libraryPaths`, `linkerOpts`) before
 * the `---` separator.
 *
 * Embedding these settings in the `.def` causes them to be stored in the resulting klib
 * and propagated automatically to all transitive consumers at link time.
 */
abstract class GenerateDefFileTask : DefaultTask() {

    /** Static template: contains `language`, `package`, `---`, and the ObjC header. */
    @get:InputFile
    abstract val templateDefFile: RegularFileProperty

    /** Absolute path to the SPM release directory that contains `lib<Package>.a`. */
    @get:Input
    abstract val libraryPath: Property<String>

    /** Full linker opts string (e.g. `-framework CryptoKit -L/path/to/toolchain/lib`). */
    @get:Input
    abstract val linkerOpts: Property<String>

    @get:OutputFile
    abstract val defFile: RegularFileProperty

    @TaskAction
    fun generate() {
        val template = templateDefFile.get().asFile.readText()
        val separatorIndex = template.indexOf("---")
        check(separatorIndex >= 0) { "Template .def file must contain a '---' separator" }

        val header = template.substring(0, separatorIndex).trimEnd()
        val body = template.substring(separatorIndex + 3) // skip "---"

        val out = defFile.get().asFile
        out.parentFile.mkdirs()
        out.writeText(buildString {
            appendLine(header)
            appendLine("staticLibraries = lib${out.nameWithoutExtension}.a")
            appendLine("libraryPaths = ${libraryPath.get()}")
            appendLine("linkerOpts = ${linkerOpts.get()}")
            append("---")
            append(body)
        })
    }
}
