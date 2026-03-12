package io.github.smyrgeorge.freepath.rust

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
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
     * Marked @Internal (not @InputDirectory) to avoid Gradle implicit-dependency errors:
     * all three iOS targets share the same `src/rust` workspace and each target's
     * outputDir is a subdirectory of this directory, which would create a circular
     * input/output overlap. Cargo handles its own incremental compilation.
     */
    @get:Internal
    abstract val cargoDir: DirectoryProperty

    /** Rust target triple, e.g. `aarch64-apple-ios`. */
    @get:Input
    abstract val rustTarget: Property<String>

    /** The release output directory: `<cargoDir>/target/<rustTarget>/release`. */
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    init {
        // cargoDir is @Internal to avoid input/output overlap (all iOS targets share the same
        // src/rust workspace). Disable Gradle's up-to-date check entirely and let cargo's own
        // incremental compilation decide whether to rebuild.
        outputs.upToDateWhen { false }
    }

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
