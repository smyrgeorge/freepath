package io.github.smyrgeorge.freepath.libble

internal object PeripheralManagerHolder {
    // Top-level lazy avoids the $init_global() codegen crash that occurs when an
    // NSObject subclass is declared as a Kotlin `object` singleton.
    val manager: PeripheralManager by lazy { PeripheralManager() }
}