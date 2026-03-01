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
 * Builds a Swift Package Manager package for a specific Apple target triple.
 * Produces `lib<PackageName>.a` inside the SPM release output directory.
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

    @TaskAction
    fun build() {
        val sdkPath = ByteArrayOutputStream().also { out ->
            execOps.exec {
                commandLine("xcrun", "--sdk", sdk.get(), "--show-sdk-path")
                standardOutput = out
            }
        }.toString().trim()

        execOps.exec {
            commandLine(
                "swift", "build",
                "--package-path", swiftPackageDir.get().asFile.absolutePath,
                "--configuration", "release",
                "--build-path", outputDir.get().asFile.absolutePath,
                "-Xswiftc", "-target",
                "-Xswiftc", swiftTarget.get(),
                "-Xswiftc", "-sdk",
                "-Xswiftc", sdkPath,
            )
        }
    }
}
