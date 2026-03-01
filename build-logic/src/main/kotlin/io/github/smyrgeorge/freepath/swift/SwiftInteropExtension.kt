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
 *     packageName    = "CryptoBridge"
 *     swiftSourceDir = "src/swift"                              // default
 *     frameworks     = listOf("CryptoKit")                      // default: empty
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
}
