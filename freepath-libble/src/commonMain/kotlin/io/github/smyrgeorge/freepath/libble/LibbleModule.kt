package io.github.smyrgeorge.freepath.libble

import com.juul.kable.Scanner
import io.github.smyrgeorge.freepath.contact.Contact
import io.github.smyrgeorge.freepath.libble.BleConstants.FREEPATH_SERVICE_UUID
import io.github.smyrgeorge.freepath.libble.LibbleEvent.Response
import io.github.smyrgeorge.freepath.libble.exchange.BleExchangeInitiator
import io.github.smyrgeorge.freepath.libble.exchange.BleExchangeResponder
import io.github.smyrgeorge.freepath.libble.exchange.BleExchangeResult
import io.github.smyrgeorge.freepath.libble.l2cap.BleL2capServer
import io.github.smyrgeorge.freepath.libble.identity.BleIdentityToken
import io.github.smyrgeorge.freepath.libble.metrics.LibbleMetrics
import io.github.smyrgeorge.freepath.libble.pool.BleConnectionPool
import io.github.smyrgeorge.freepath.libble.pool.BleFrameType
import io.github.smyrgeorge.freepath.libble.pool.BleL2capChannel
import io.github.smyrgeorge.freepath.util.rpc.RpcManager
import io.github.smyrgeorge.log4k.Logger
import io.github.smyrgeorge.log4k.impl.extensions.doEvery
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.onFailure
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalAtomicApi::class)
class LibbleModule {

    private val log = Logger.of(this::class)

    val metrics: LibbleMetrics = LibbleMetrics()
    val requests: Channel<LibbleEvent.RequestReceived> = Channel(
        capacity = 1000,
        onBufferOverflow = BufferOverflow.DROP_LATEST,
    )
    private val started = AtomicBoolean(false)

    @Volatile
    private var handler: (suspend (LibbleEvent) -> Unit)? = null

    private lateinit var localPeerId: String
    private lateinit var localPeerIdBytes: ByteArray
    private lateinit var peripheralIdLookup: suspend (peerId: String) -> String?

    /** Returns peerId → identitySecret (Base64-decoded 32 bytes) for all contacts with a BLE identity secret. */
    private lateinit var contactSecretsLookup: suspend () -> Map<String, ByteArray>

    /** Called when a token-matched peripheral is identified — updates blePeripheralId in the routing table. */
    private lateinit var onPeripheralIdentified: suspend (peerId: String, peripheralId: String) -> Unit

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val rpc = RpcManager<Response>()

    private lateinit var expiryJob: Job
    private lateinit var tokenRotationJob: Job
    private val advertiser: LibbleAdvertiser = LibbleAdvertiser()
    private val l2capServer: BleL2capServer = BleL2capServer()
    private var lastAdvertisedEpoch: Long = -1

    // Cache for contactSecretsLookup — refreshed periodically, not on every advertisement.
    private var cachedSecrets: Map<String, ByteArray> = emptyMap()
    private var cachedSecretsEpoch: Long = -1

    // reqId → peripheralId: set when a REQUEST frame arrives; consumed by sendResponse/sendResponseFailed.
    private val reqIdToPeripheralId = HashMap<Long, String>()
    private val reqIdMutex = Mutex()

    // Inbound channels queued for beginResponderExchange.
    private val inboundChannelQueue: Channel<InboundEntry> = Channel(Channel.UNLIMITED)

    private val peripherals = mutableMapOf<String, PeripheralEntry>()
    private val psmCache = HashMap<String, Int>()
    private val peripheralsMutex = Mutex()

    private val pool = BleConnectionPool(
        scope = scope,
        rpc = rpc,
        psmLookup = { peripheralId ->
            peripheralsMutex.withLock {
                peripherals[peripheralId]?.psm ?: psmCache[peripheralId]
            }
        },
        onRequestReceived = { peripheralId, reqId, payload, senderPeerId ->
            // Store the mapping before enqueuing the request so that
            // sendResponse/sendResponseFailed can route the response back via the L2CAP channel.
            reqIdMutex.withLock { reqIdToPeripheralId[reqId] = peripheralId }
            // Use senderPeerId (from the request frame header) as the sender identity,
            // not the OS-level peripheralId (which differs between central/peripheral roles on iOS).
            val req = LibbleEvent.RequestReceived(senderPeerId, reqId, payload)
            requests.trySend(req).onFailure {
                log.error { "Failed to send message to requests channel: $it" }
            }
            scope.launch { sendEvent(req) }
        },
        onPeerRemoved = { peerId ->
            log.debug("Pool entry removed for peerId=$peerId — emitting PeerLost")
            sendEvent(LibbleEvent.PeerLost(peerId))
        },
    )

    /**
     * @param peripheralIdLookup resolves a known peerId to the BLE peripheral ID (from routing table).
     * @param contactSecretsLookup returns all contacts' identity secrets: peerId → 32-byte secret.
     *        The same map is used for both scanning (match incoming tokens) and advertising (rotate
     *        through secrets so each contact can identify this device in turn).
     * @param onPeripheralIdentified called when a scanned token matches a known contact — updates the routing table's blePeripheralId.
     */
    suspend fun start(
        localPeerId: String,
        peripheralIdLookup: suspend (peerId: String) -> String?,
        contactSecretsLookup: suspend () -> Map<String, ByteArray>,
        onPeripheralIdentified: suspend (peerId: String, peripheralId: String) -> Unit,
    ) {
        if (!started.compareAndSet(expectedValue = false, newValue = true)) return
        this.localPeerId = localPeerId
        this.localPeerIdBytes = localPeerId.encodeToByteArray()
        this.peripheralIdLookup = peripheralIdLookup
        this.contactSecretsLookup = contactSecretsLookup
        this.onPeripheralIdentified = onPeripheralIdentified
        expiryJob = doEvery(1.seconds) {
            val now = Clock.System.now()
            val expired = mutableListOf<Pair<String, PeripheralEntry>>()
            peripheralsMutex.withLock {
                peripherals
                    .filter { (_, e) -> now - e.event.discoveredAt > ADVERTISEMENT_EXPIRE_THRESHOLD }
                    .forEach { (id, entry) ->
                        peripherals.remove(id)
                        expired += id to entry
                    }
            }
            expired.forEach { (id, entry) ->
                reqIdMutex.withLock { reqIdToPeripheralId.entries.removeAll { it.value == id } }
                pool.remove(id)
                sendEvent(LibbleEvent.PeripheralExpired(id))
                entry.matchedPeerId?.let { peerId -> sendEvent(LibbleEvent.PeerLost(peerId)) }
                log.debug("Removed expired peripheral: $id")
            }
        }
        // Token rotation: check every minute if the epoch changed; restart advertising with new token.
        tokenRotationJob = doEvery(TOKEN_ROTATION_CHECK_INTERVAL) {
            refreshAdvertiserToken()
        }
        log.info("LibbleModule starting")
        l2capServer.start()
        val token = computeOwnToken()
        advertiser.start(l2capServer.psm, token)
        scope.launch { acceptLoop() }
        scope.launch { scanLoop() }
    }

    fun setEventHandler(handler: suspend (LibbleEvent) -> Unit): LibbleModule {
        this.handler = handler
        return this
    }

    /**
     * Look up the peerId for a given peripheralId using the in-memory token match cache.
     * Returns null if the peripheral hasn't been matched to any contact.
     */
    suspend fun peerIdForPeripheral(peripheralId: String): String? =
        peripheralsMutex.withLock { peripherals[peripheralId]?.matchedPeerId }

    suspend fun request(timeout: Duration, peerId: String, payload: ByteArray): Response =
        rpc.request(timeout) { sendRequest(peerId, it, payload) }

    suspend fun sendRequest(peerId: String, reqId: Long, payload: ByteArray) {
        suspend fun sendVia(targetId: String) {
            pool.withConnection(targetId) { channel, _ ->
                // Tag the entry now that it exists, so future lookups by peerId can find it.
                pool.setPeerIdForEntry(targetId, peerId)
                // Frame: [8-byte LE reqId] [1-byte peerIdLen] [peerId bytes] [payload]
                val frame = ByteArray(8) { i -> (reqId shr (i * 8)).toByte() } +
                        byteArrayOf(localPeerIdBytes.size.toByte()) + localPeerIdBytes + payload
                channel.send(BleFrameType.REQUEST, frame)
            }
        }

        val peripheralId = resolvePeripheralId(peerId)
        log.debug("sendRequest: peerId=$peerId → peripheralId=$peripheralId reqId=$reqId (${payload.size} bytes)")
        try {
            sendVia(peripheralId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Connection failed — remove stale pool entry and try token-based fallback.
            log.warn("sendRequest: connection to $peripheralId failed (${e.message}), trying fallback")
            pool.remove(peripheralId)
            val fallbackId = resolvePeripheralIdFallback(peerId, excludeId = peripheralId)
            sendVia(fallbackId)
        }
    }

    /**
     * Fallback peripheral resolution: skip tier 1 (routing table) and go straight to
     * in-memory token matching, excluding a known-stale [excludeId].
     */
    private suspend fun resolvePeripheralIdFallback(peerId: String, excludeId: String): String {
        val cachedMatch = peripheralsMutex.withLock {
            peripherals.entries.firstOrNull { (id, entry) ->
                id != excludeId && entry.matchedPeerId == peerId
            }?.key
        }
        if (cachedMatch != null) {
            log.info("resolvePeripheralIdFallback: matched peerId=$peerId → $cachedMatch")
            onPeripheralIdentified(peerId, cachedMatch)
            return cachedMatch
        }

        val secret = contactSecretsLookup()[peerId]
            ?: error("No identity secret for peerId=$peerId")
        val now = Clock.System.now().toEpochMilliseconds()
        val tokenMatch = peripheralsMutex.withLock {
            peripherals.entries.firstNotNullOfOrNull { (id, entry) ->
                if (id == excludeId) return@firstNotNullOfOrNull null
                val token = entry.identityToken ?: return@firstNotNullOfOrNull null
                val matched = BleIdentityToken.match(token, mapOf(peerId to secret), now)
                if (matched != null) id else null
            }
        }
        if (tokenMatch != null) {
            log.info("resolvePeripheralIdFallback: token matched peerId=$peerId → $tokenMatch")
            peripheralsMutex.withLock {
                peripherals[tokenMatch]?.let { entry ->
                    peripherals[tokenMatch] = entry.copy(matchedPeerId = peerId)
                }
            }
            onPeripheralIdentified(peerId, tokenMatch)
            return tokenMatch
        }

        error("No nearby BLE peripheral found for peerId=$peerId after fallback")
    }

    suspend fun sendResponse(reqId: Long, payload: ByteArray) {
        val peripheralId = reqIdMutex.withLock { reqIdToPeripheralId.remove(reqId) }
            ?: error("No peripheral found for reqId=$reqId")
        log.debug("sendResponse: reqId=$reqId → peripheralId=$peripheralId (${payload.size} bytes)")
        pool.withConnection(peripheralId) { channel, _ ->
            val frame = ByteArray(8) { i -> (reqId shr (i * 8)).toByte() } + byteArrayOf(0x00) + payload
            channel.send(BleFrameType.RESPONSE, frame)
        }
    }

    suspend fun sendResponseFailed(reqId: Long, error: String) {
        val peripheralId = reqIdMutex.withLock { reqIdToPeripheralId.remove(reqId) }
            ?: error("No peripheral found for reqId=$reqId")
        log.warn("sendResponseFailed: reqId=$reqId → peripheralId=$peripheralId error=$error")
        pool.withConnection(peripheralId) { channel, _ ->
            val errorBytes = error.encodeToByteArray()
            val frame = ByteArray(8) { i -> (reqId shr (i * 8)).toByte() } + byteArrayOf(0x01) + errorBytes
            channel.send(BleFrameType.RESPONSE, frame)
        }
    }

    /**
     * Resolve the BLE peripheral ID for a peer. Checks multiple sources:
     * 0. Existing pool entry (reuse an inbound channel — avoids opening a second BLE connection)
     * 1. Routing table
     * 2. In-memory scan token matches
     * 3. Raw token re-match against unmatched peripherals
     */
    private suspend fun resolvePeripheralId(peerId: String): String {
        // Tier 0: check if the pool already has a channel for this peer under ANY peripheralId.
        // This handles the iOS case where an inbound channel (keyed by CBCentral.identifier)
        // should be reused instead of opening an outbound channel (keyed by CBPeripheral.identifier).
        pool.findEntryForPeer(peerId)?.let {
            log.debug("resolvePeripheralId: reusing existing pool entry $it for peerId=$peerId")
            return it
        }

        // Tier 1: routing table has a current peripheral ID.
        peripheralIdLookup(peerId)?.let { return it }

        // Tier 2: check if the scan loop already matched a peripheral to this peer.
        val cachedMatch = peripheralsMutex.withLock {
            peripherals.entries.firstOrNull { (_, entry) ->
                entry.matchedPeerId == peerId
            }?.key
        }
        if (cachedMatch != null) {
            log.info("resolvePeripheralId: found cached match peerId=$peerId → $cachedMatch")
            onPeripheralIdentified(peerId, cachedMatch)
            return cachedMatch
        }

        // Tier 3: try to match unmatched peripheral tokens against this peer's secret.
        val secret = contactSecretsLookup()[peerId]
            ?: error("No BLE peripheral ID or identity secret found for peerId=$peerId")

        val now = Clock.System.now().toEpochMilliseconds()
        val tokenMatch = peripheralsMutex.withLock {
            peripherals.entries.firstNotNullOfOrNull { (id, entry) ->
                val token = entry.identityToken ?: return@firstNotNullOfOrNull null
                val matched = BleIdentityToken.match(token, mapOf(peerId to secret), now)
                if (matched != null) id else null
            }
        }
        if (tokenMatch != null) {
            log.info("resolvePeripheralId: token fallback matched peerId=$peerId → $tokenMatch")
            peripheralsMutex.withLock {
                peripherals[tokenMatch]?.let { entry ->
                    peripherals[tokenMatch] = entry.copy(matchedPeerId = peerId)
                }
            }
            onPeripheralIdentified(peerId, tokenMatch)
            return tokenMatch
        }

        error("No nearby BLE peripheral found for peerId=$peerId — peer may be out of range")
    }

    suspend fun stop() {
        if (!started.compareAndSet(expectedValue = true, newValue = false)) return
        log.info("LibbleModule stopping")
        expiryJob.cancel()
        tokenRotationJob.cancel()
        advertiser.stop()
        pool.close()
        l2capServer.stop()
        metrics.close()
        scope.cancel()
    }

    /**
     * Compute the own identity token for the current epoch.
     *
     * Each device shares a different identity secret with each contact. Since we can only
     * advertise one token at a time, we rotate through contacts' secrets across epochs:
     * `secret = secrets[epoch % secrets.size]`. With 15-minute rotation and N contacts,
     * each contact can identify this device once every 15*N minutes.
     */
    private suspend fun computeOwnToken(): ByteArray? {
        val secrets = contactSecretsLookup().values.toList()
        if (secrets.isEmpty()) return null
        val now = Clock.System.now().toEpochMilliseconds()
        val epoch = now / BleIdentityToken.ROTATION_INTERVAL_MS
        lastAdvertisedEpoch = epoch
        // Rotate through secrets so each contact gets a chance to identify this device.
        val secret = secrets[(epoch % secrets.size).toInt()]
        val token = BleIdentityToken.compute(secret, now)
        val hex = token.joinToString("") { it.toUByte().toString(16).padStart(2, '0') }
        metrics.updateAdvertisedToken(hex)
        return token
    }

    /** Restart advertising if the token epoch has changed. */
    private suspend fun refreshAdvertiserToken() {
        val now = Clock.System.now().toEpochMilliseconds()
        val currentEpoch = now / BleIdentityToken.ROTATION_INTERVAL_MS
        if (currentEpoch != lastAdvertisedEpoch) {
            log.info("Token epoch rotated ($lastAdvertisedEpoch → $currentEpoch), restarting advertiser")
            advertiser.stop()
            val token = computeOwnToken()
            advertiser.start(l2capServer.psm, token)
        }
    }

    suspend fun beginInitiatorExchange(
        peripheralId: String,
        pin: String,
        localContact: Contact,
        sigKeyPrivate: ByteArray,
    ): Result<BleExchangeResult> = runCatching {
        pool.withConnection(peripheralId) { channel, exchangeFrames ->
            val r = BleExchangeInitiator(
                sendExchange = { bytes -> channel.send(BleFrameType.EXCHANGE, bytes) },
                exchangeFrames = exchangeFrames,
                pin = pin,
            ).run(localContact, sigKeyPrivate) { sendEvent(it) }
                .getOrThrow()
            BleExchangeResult(r.contact, peripheralId, r.identitySecret)
        }
    }.also { result ->
        result.exceptionOrNull()?.let {
            if (it is CancellationException) throw it
        }
    }

    /**
     * Runs the responder side of the secure exchange.
     * Waits for the next inbound L2CAP channel from the accept loop.
     * Returns the exchanged [Contact] paired with the remote peripheral's ID.
     */
    suspend fun beginResponderExchange(
        pin: String,
        localContact: Contact,
        sigKeyPrivate: ByteArray,
    ): Result<BleExchangeResult> = runCatching {
        val (channel, exchangeFrames) = inboundChannelQueue.receive()
        val peripheralId = channel.peripheralId
        val r = BleExchangeResponder(
            sendExchange = { bytes -> channel.send(BleFrameType.EXCHANGE, bytes) },
            exchangeFrames = exchangeFrames,
            pin = pin,
        ).run(localContact, sigKeyPrivate) { sendEvent(it) }
            .getOrThrow()
        BleExchangeResult(r.contact, peripheralId, r.identitySecret)
    }.also { result ->
        result.exceptionOrNull()?.let {
            if (it is CancellationException) throw it
        }
    }

    private suspend fun acceptLoop() {
        runCatching {
            l2capServer.incoming.collect { channel ->
                val exchangeFrames = pool.registerInbound(channel)
                // Tag the inbound entry with the peerId if we can match the peripheralId.
                // On iOS, channel.peripheralId is CBCentral.identifier (different from
                // CBPeripheral.identifier used by the scan loop). Try all known mappings.
                val peerId = peerIdForPeripheral(channel.peripheralId)
                    ?: peripheralsMutex.withLock {
                        // The inbound peripheralId might not match any scan result.
                        // Check all matched peers — if we have only one contact, it's likely them.
                        peripherals.values.mapNotNull { it.matchedPeerId }.distinct().singleOrNull()
                    }
                if (peerId != null) {
                    pool.setPeerIdForEntry(channel.peripheralId, peerId)
                    log.info("acceptLoop: tagged inbound ${channel.peripheralId} as peerId=$peerId")
                }
                inboundChannelQueue.trySend(InboundEntry(channel, exchangeFrames))
            }
        }.onFailure { e ->
            if (e is CancellationException) throw e
            log.error { "Accept loop terminated unexpectedly: $e" }
        }
    }

    private suspend fun scanLoop() {
        val scanner = Scanner {
            filters {
                match { services = listOf(FREEPATH_SERVICE_UUID) }
            }
        }
        scanner.advertisements.collect { advertisement ->
            val peripheralId = advertisement.identifier.toString()
            val isNew = peripheralsMutex.withLock { peripheralId !in peripherals }

            // Extract PSM and identity token from service data (Android) or local name (iOS).
            val serviceData = advertisement.serviceData(FREEPATH_SERVICE_UUID)
            val psm: Int?
            var identityToken: ByteArray? = null

            if (serviceData != null && serviceData.size >= 2) {
                // Android: [PSM 2 bytes LE] [token 8 bytes (optional)]
                psm = (serviceData[0].toInt() and 0xFF) or ((serviceData[1].toInt() and 0xFF) shl 8)
                if (serviceData.size >= 2 + BleIdentityToken.TOKEN_LEN) {
                    identityToken = serviceData.copyOfRange(2, 2 + BleIdentityToken.TOKEN_LEN)
                }
            } else {
                // iOS: "fp:PPPP" or "fp:PPPP:TTTTTTTTTTTTTTTT"
                val match = advertisement.name?.let { AD_PATTERN.find(it) }
                psm = match?.groupValues?.get(1)?.toIntOrNull(16)
                match?.groupValues?.get(2)?.takeIf { it.isNotEmpty() }?.let { hex ->
                    identityToken = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                        .takeIf { it.size == BleIdentityToken.TOKEN_LEN }
                }
            }

            // Try to match the identity token against known contacts.
            var matchedPeerId: String? = null
            if (identityToken != null) {
                val now = Clock.System.now().toEpochMilliseconds()
                val epoch = now / SECRETS_CACHE_INTERVAL_MS
                if (epoch != cachedSecretsEpoch) {
                    cachedSecrets = contactSecretsLookup()
                    cachedSecretsEpoch = epoch
                }
                matchedPeerId = BleIdentityToken.match(identityToken!!, cachedSecrets, now)
            }

            val tokenHex = identityToken?.joinToString("") { it.toUByte().toString(16).padStart(2, '0') }
            val event = LibbleEvent.PeripheralDiscovered(
                discoveredAt = Clock.System.now(),
                peripheralId = peripheralId,
                peripheralName = advertisement.peripheralName,
                name = advertisement.name,
                rssi = advertisement.rssi,
                txPower = advertisement.txPower,
                isConnectable = advertisement.isConnectable,
                identityTokenHex = tokenHex,
            )
            // Track whether this is the first time we match this peripheral to a peer.
            val isFirstMatch: Boolean
            peripheralsMutex.withLock {
                val existing = peripherals[peripheralId]
                val effectivePsm = psm ?: existing?.psm
                val previousMatch = existing?.matchedPeerId
                val effectiveMatch = matchedPeerId ?: previousMatch
                val effectiveToken = identityToken ?: existing?.identityToken
                peripherals[peripheralId] = PeripheralEntry(
                    event = event, psm = effectivePsm,
                    matchedPeerId = effectiveMatch, identityToken = effectiveToken,
                )
                if (effectivePsm != null) psmCache[peripheralId] = effectivePsm
                isFirstMatch = matchedPeerId != null && previousMatch == null
            }
            log.debug("scanLoop: id=$peripheralId name=${advertisement.name} psm=$psm matched=$matchedPeerId")
            if (isNew) {
                sendEvent(event)
            }
            if (isFirstMatch && matchedPeerId != null) {
                // Update routing table with the current peripheralId for this peer.
                onPeripheralIdentified(matchedPeerId, peripheralId)
                sendEvent(LibbleEvent.PeerIdentified(matchedPeerId, peripheralId))
            }
        }
    }

    private suspend fun sendEvent(event: LibbleEvent) {
        metrics.onEvent(event)
        handler?.let { h -> runCatching { h(event) } }
    }

    private data class PeripheralEntry(
        val event: LibbleEvent.PeripheralDiscovered,
        val psm: Int?,
        val matchedPeerId: String? = null,
        /** Raw 8-byte identity token extracted from the advertisement, if present. */
        val identityToken: ByteArray? = null,
    )

    private data class InboundEntry(
        val channel: BleL2capChannel,
        val exchangeFrames: Channel<ByteArray>,
    )

    companion object {
        // iOS delivers at most one advertisement per peripheral per scan session (allowDuplicatesKey=false),
        // so discoveredAt is never refreshed. Use a generous threshold to avoid premature expiry.
        private val ADVERTISEMENT_EXPIRE_THRESHOLD = 30.seconds

        // Check every minute if the token epoch has rotated.
        private val TOKEN_ROTATION_CHECK_INTERVAL = 1.minutes

        // Refresh secrets cache every 30 seconds (not on every advertisement).
        private const val SECRETS_CACHE_INTERVAL_MS = 30_000L

        // Matches "fp:PPPP" or "fp:PPPP:TTTTTTTTTTTTTTTT" anywhere in the advertisement name.
        // Group 1: PSM hex (1-4 digits). Group 2: optional token hex (16 hex chars = 8 bytes).
        private val AD_PATTERN = Regex("fp:([0-9a-fA-F]{1,4})(?::([0-9a-fA-F]{16}))?")
    }
}
