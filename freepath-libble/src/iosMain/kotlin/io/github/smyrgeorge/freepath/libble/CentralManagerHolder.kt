package io.github.smyrgeorge.freepath.libble

internal object CentralManagerHolder {
    // Top-level lazy avoids the $init_global() codegen crash that occurs when an
    // NSObject subclass is declared as a Kotlin `object` singleton.
    val manager: CentralManager by lazy { CentralManager() }

    /** Force initialization so the background scan starts early. */
    fun ensureInitialized() {
        manager // trigger lazy init
    }
}
