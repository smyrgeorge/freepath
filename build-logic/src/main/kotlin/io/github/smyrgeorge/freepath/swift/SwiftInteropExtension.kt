package io.github.smyrgeorge.freepath.swift

/**
 * DSL extension exposed as `swiftInterop { }` in consuming modules.
 *
 * Minimal required configuration:
 * ```kotlin
 * swiftInterop {
 *     packageName = "CryptoBridge"
 * }
 * ```
 *
 * Optional overrides:
 * ```kotlin
 * swiftInterop {
 *     packageName        = "CryptoBridge"
 *     swiftSourceDir     = "src/swift"                              // default
 *     frameworks         = listOf("CryptoKit")                      // default: empty
 *     cDependencyTargets = listOf("Cwasm3")                         // default: empty
 *     cDefines           = listOf("d_m3MaxFunctionStackHeight=2000") // default: empty
 *     // templateDefFile defaults to src/nativeInterop/cinterop/<packageName>.def
 * }
 * ```
 */
open class SwiftInteropExtension {
    /** SPM target name and cinterop name (e.g. `"CryptoBridge"`). Required. */
    var packageName: String = ""

    /** Project-relative path to the directory containing `Package.swift`. */
    var swiftSourceDir: String = "src/swift"

    /**
     * Apple system frameworks to link (without the `-framework` prefix).
     * Each entry becomes `-framework <name>` in `linkerOpts`.
     */
    var frameworks: List<String> = emptyList()

    /**
     * Project-relative path to the cinterop template `.def` file.
     * Defaults to `src/nativeInterop/cinterop/<packageName>.def` when `null`.
     */
    var templateDefFile: String? = null

    /**
     * Optional list of C source target names under `Sources/` within the Swift package
     * that should be compiled and merged into the final static library.
     * Each entry is expected to have its public headers under `Sources/<cTarget>/include/`.
     */
    var cDependencyTargets: List<String> = emptyList()

    /**
     * Optional list of C preprocessor defines (without the `-D` prefix) forwarded to
     * `clang` when compiling the [cDependencyTargets].
     * Example: `listOf("d_m3MaxFunctionStackHeight=2000", "d_m3VerboseErrorMessages")`.
     */
    var cDefines: List<String> = emptyList()
}
