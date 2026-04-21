package io.github.smyrgeorge.freepath.rust

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.File
import javax.inject.Inject

/**
 * Runs `cargo build --release --target <rustTarget>` for a single Rust target triple.
 * Output lands at `<cargoDir>/target/<rustTarget>/release/`.
 */
abstract class RustBuildTask @Inject constructor(
    private val execOps: ExecOperations,
) : DefaultTask() {

    /**
     * Directory containing Cargo.toml.
     * Marked @Internal (not @InputDirectory) to avoid input/output overlap: outputDir is
     * `<cargoDir>/target/...`. Source tracking for up-to-date checks is done via [sources],
     * which explicitly excludes `target/`.
     */
    @get:Internal
    abstract val cargoDir: DirectoryProperty

    /**
     * Rust source files whose changes should invalidate the build (Cargo.toml, Cargo.lock,
     * build.rs, cbindgen.toml, plus files under src/). Must exclude `target/` to avoid
     * input/output overlap with [outputDir].
     */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    /** Rust target triple, e.g. `aarch64-apple-ios`. */
    @get:Input
    abstract val rustTarget: Property<String>

    /** The release output directory: `<cargoDir>/target/<rustTarget>/release`. */
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun build() {
        execOps.exec {
            workingDir(cargoDir.get().asFile)
            commandLine(resolveCargo(), "build", "--release", "--target", rustTarget.get())
        }
    }

    companion object {
        fun resolveCargo(): String =
            File(System.getProperty("user.home"), ".cargo/bin/cargo")
                .takeIf { it.exists() }?.absolutePath ?: "cargo"
    }
}
