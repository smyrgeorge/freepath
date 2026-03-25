package io.github.smyrgeorge.freepath.state

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlin.coroutines.EmptyCoroutineContext

fun String.abbrev(): String = takeLast(10)

fun <T> Flow<T>.stateIn(initialValue: T): StateFlow<T> =
    stateIn(CoroutineScope(EmptyCoroutineContext), SharingStarted.Eagerly, initialValue)
