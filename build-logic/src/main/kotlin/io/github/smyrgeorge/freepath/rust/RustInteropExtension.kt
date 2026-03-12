package io.github.smyrgeorge.freepath.rust

/**
 * DSL extension exposed as `rustInterop { }` in consuming modules.
 *
 * Minimal required:
 * ```kotlin
 * rustInterop {
 *     crateName = "freepath_libp2p"
 * }
 * ```
 */
open class RustInteropExtension {
    /** Rust crate name (snake_case), e.g. `freepath_libp2p`. Required. */
    var crateName: String = ""

    /** Project-relative path to the directory containing Cargo.toml. Default: `src/rust`. */
    var cargoDir: String = "src/rust"

    /**
     * Project-relative path to the cinterop template `.def` file.
     * Default: `src/nativeInterop/cinterop/<crateName>.def`
     */
    var templateDefFile: String? = null

    /**
     * Project-relative path to the directory containing the cbindgen-generated `.h` header.
     * Default: `src/nativeInterop/cinterop`
     */
    var headerDir: String = "src/nativeInterop/cinterop"

    /** Extra linker opts (e.g. `-framework Security`). Empty = none. */
    var linkerOpts: String = ""
}
