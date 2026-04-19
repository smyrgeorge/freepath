package io.github.smyrgeorge.freepath.util.crypto

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.providers.jdk.JDK
import org.bouncycastle.jce.provider.BouncyCastleProvider

internal actual val cryptographyProvider: CryptographyProvider = CryptographyProvider.JDK(BouncyCastleProvider())
