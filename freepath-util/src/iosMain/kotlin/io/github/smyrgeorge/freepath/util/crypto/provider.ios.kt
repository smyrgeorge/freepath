package io.github.smyrgeorge.freepath.util.crypto

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.providers.cryptokit.CryptoKit

internal actual val cryptographyProvider: CryptographyProvider = CryptographyProvider.CryptoKit
