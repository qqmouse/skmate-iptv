package tv.own.owntv.plugin.stalkermacpool.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import tv.own.owntv.core.stalker.StalkerClient
import tv.own.owntv.plugin.stalkermacpool.data.StalkerMacPoolStore
import tv.own.owntv.plugin.stalkermacpool.model.ServerSpeedStatus
import tv.own.owntv.plugin.stalkermacpool.model.StalkerMacPoolDefaults
import tv.own.owntv.plugin.stalkermacpool.model.StalkerMacStatus
import tv.own.owntv.plugin.stalkermacpool.model.StalkerServerGroup
import tv.own.owntv.plugin.stalkermacpool.playback.StalkerMacRotationPolicy
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * Strictly single-threaded (sequential) two-stage speed tester:
 * - Stage 1: Sequential handshake + get_profile RTT latency probe per server (ignores JSON `proxy`).
 * - Stage 2: Sequential short-stream download probe (~512 KB / 2.5s cap) to measure TTFB and KB/s.
 */
class StalkerSpeedTester(
    private val store: StalkerMacPoolStore,
    baseOkHttpClient: OkHttpClient,
) {
    enum class Phase { IDLE, STAGE1_PING, STAGE2_STREAM }

    data class SpeedTestProgress(
        val running: Boolean = false,
        val phase: Phase = Phase.IDLE,
        val currentIndex: Int = 0,
        val totalCount: Int = 0,
        val currentHost: String = "",
        val aliveCount: Int = 0,
        val doneCount: Int = 0,
    )

    private data class AliveTarget(
        val portalKey: String,
        val displayHost: String,
        val apiBase: String,
        val token: String,
        val mac: String,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val speedOkHttp: OkHttpClient = baseOkHttpClient.newBuilder()
        .connectTimeout(StalkerMacPoolDefaults.PING_TIMEOUT_SEC, TimeUnit.SECONDS)
        .readTimeout(StalkerMacPoolDefaults.PING_TIMEOUT_SEC, TimeUnit.SECONDS)
        .writeTimeout(StalkerMacPoolDefaults.PING_TIMEOUT_SEC, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    private val speedStalkerClient = StalkerClient(speedOkHttp)

    private val _progress = MutableStateFlow(SpeedTestProgress())
    val progress: StateFlow<SpeedTestProgress> = _progress.asStateFlow()

    @Volatile
    private var activeJob: Job? = null

    fun testAll() {
        startSequential {
            store.snapshot.value.servers.map { it.portalKey }
        }
    }

    fun testUntested() {
        startSequential {
            store.snapshot.value.servers
                .filter { it.speedStatus == ServerSpeedStatus.UNTESTED || it.downloadKbps == null }
                .map { it.portalKey }
        }
    }

    fun testSingle(portalKey: String) {
        startSequential { listOf(portalKey) }
    }

    fun cancel() {
        activeJob?.cancel()
        activeJob = null
        _progress.value = SpeedTestProgress()
        scope.launch { resetInProgressStatuses() }
    }

    private fun startSequential(resolveKeys: () -> List<String>) {
        activeJob?.cancel()
        activeJob = scope.launch {
            store.ensureLoaded()
            val portalKeys = resolveKeys()
            if (portalKeys.isEmpty()) return@launch
            runSequentialTwoStage(portalKeys)
        }
    }

    private suspend fun runSequentialTwoStage(portalKeys: List<String>) {
        val aliveTargets = ArrayList<AliveTarget>()
        val total = portalKeys.size

        try {
            // Stage 1: Sequential connectivity + RTT probe (strictly 1 request chain at a time).
            for ((index, portalKey) in portalKeys.withIndex()) {
                coroutineContext.ensureActive()
                val group = store.snapshot.value.servers.firstOrNull { it.portalKey == portalKey } ?: continue
                _progress.value = SpeedTestProgress(
                    running = true,
                    phase = Phase.STAGE1_PING,
                    currentIndex = index + 1,
                    totalCount = total,
                    currentHost = group.displayHost,
                    aliveCount = aliveTargets.size,
                    doneCount = 0,
                )
                store.updateServerSpeed(portalKey, persist = false) {
                    it.copy(speedStatus = ServerSpeedStatus.TESTING_PING, lastSpeedError = null)
                }

                val alive = probeServerConnectivity(group)
                if (alive != null) {
                    aliveTargets.add(alive)
                }
            }

            // Stage 2: Sequential real stream throughput on Stage-1 reachable servers.
            val aliveTotal = aliveTargets.size
            for ((index, target) in aliveTargets.withIndex()) {
                coroutineContext.ensureActive()
                _progress.value = SpeedTestProgress(
                    running = true,
                    phase = Phase.STAGE2_STREAM,
                    currentIndex = index + 1,
                    totalCount = aliveTotal,
                    currentHost = target.displayHost,
                    aliveCount = aliveTotal,
                    doneCount = index,
                )
                store.updateServerSpeed(target.portalKey, persist = false) {
                    it.copy(speedStatus = ServerSpeedStatus.TESTING_STREAM)
                }

                probeServerStreamSpeed(target)
            }
        } catch (c: CancellationException) {
            resetInProgressStatuses()
            throw c
        } finally {
            _progress.value = SpeedTestProgress()
        }
    }

    private suspend fun probeServerConnectivity(group: StalkerServerGroup): AliveTarget? {
        val now = System.currentTimeMillis()
        val ranked = StalkerMacRotationPolicy.rankCandidates(
            macs = group.macs,
            currentMac = null,
            nowMs = now,
            includeFallbackInvalid = true,
        )
        val candidates = if (group.workingMacHint != null) {
            val hintEntry = ranked.firstOrNull { it.mac == group.workingMacHint }
            listOfNotNull(hintEntry) + ranked.filterNot { it.mac == group.workingMacHint }
        } else {
            ranked
        }.take(MAX_MAC_PROBES_PER_SERVER)

        if (candidates.isEmpty()) {
            store.updateServerSpeed(group.portalKey, persist = true) {
                it.copy(
                    speedStatus = ServerSpeedStatus.UNREACHABLE,
                    downloadKbps = null,
                    pingMs = null,
                    ttfbMs = null,
                    lastSpeedTestAtMs = System.currentTimeMillis(),
                    lastSpeedError = "no candidate MAC",
                )
            }
            return null
        }

        var lastError: String? = null
        val ua = StalkerClient.DEFAULT_MAG_USER_AGENT

        for (candidate in candidates) {
            coroutineContext.ensureActive()
            val t0 = System.nanoTime()
            try {
                val hs = speedStalkerClient.resolveHandshake(group.portalKey, candidate.mac, ua)
                speedStalkerClient.getProfile(hs.apiBase, candidate.mac, hs.token, ua)
                val rttMs = ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(1L)

                store.updateMacState(group.portalKey, candidate.mac, StalkerMacStatus.OK, 0L, null)
                store.updateServerSpeed(group.portalKey, persist = true) {
                    it.copy(
                        speedStatus = ServerSpeedStatus.OK,
                        pingMs = rttMs,
                        workingMacHint = candidate.mac,
                        lastSpeedError = null,
                        lastSpeedTestAtMs = System.currentTimeMillis(),
                    )
                }
                return AliveTarget(
                    portalKey = group.portalKey,
                    displayHost = group.displayHost,
                    apiBase = hs.apiBase,
                    token = hs.token,
                    mac = candidate.mac,
                )
            } catch (c: CancellationException) {
                throw c
            } catch (e: StalkerClient.StalkerAuthException) {
                lastError = e.message ?: e::class.java.simpleName
                store.updateMacState(
                    group.portalKey,
                    candidate.mac,
                    StalkerMacStatus.AUTH_FAILED,
                    0L,
                    lastError,
                )
            } catch (e: Exception) {
                lastError = e.message ?: e::class.java.simpleName
                // Network/timeout/HTTP errors usually apply to the whole host; stop retrying MACs on same host.
                break
            }
        }

        store.updateServerSpeed(group.portalKey, persist = true) {
            it.copy(
                speedStatus = ServerSpeedStatus.UNREACHABLE,
                pingMs = null,
                ttfbMs = null,
                downloadKbps = null,
                lastSpeedError = lastError?.take(120),
                lastSpeedTestAtMs = System.currentTimeMillis(),
            )
        }
        return null
    }

    private suspend fun probeServerStreamSpeed(target: AliveTarget) {
        val ua = StalkerClient.DEFAULT_MAG_USER_AGENT
        val sampleChannels = runCatching {
            val wildcardPage = speedStalkerClient.getLiveChannelsPage(
                target.apiBase,
                target.mac,
                target.token,
                ua,
                "*",
                1,
            ).items
            if (wildcardPage.isNotEmpty()) {
                wildcardPage
            } else {
                val genres = speedStalkerClient.getGenres(target.apiBase, target.mac, target.token, ua)
                    .filter { it.id.isNotBlank() && it.id != "*" }
                var picked = emptyList<StalkerClient.Channel>()
                for (g in genres.take(3)) {
                    val page = speedStalkerClient.getLiveChannelsPage(
                        target.apiBase,
                        target.mac,
                        target.token,
                        ua,
                        g.id,
                        1,
                    ).items
                    if (page.isNotEmpty()) {
                        picked = page
                        break
                    }
                }
                picked
            }
        }.getOrElse { e ->
            if (e is CancellationException) throw e
            store.updateServerSpeed(target.portalKey, persist = true) {
                it.copy(
                    speedStatus = ServerSpeedStatus.OK,
                    downloadKbps = null,
                    ttfbMs = null,
                    lastSpeedError = e.message?.take(120),
                    lastSpeedTestAtMs = System.currentTimeMillis(),
                )
            }
            return
        }

        if (sampleChannels.isEmpty()) {
            store.updateServerSpeed(target.portalKey, persist = true) {
                it.copy(
                    speedStatus = ServerSpeedStatus.OK,
                    downloadKbps = null,
                    ttfbMs = null,
                    lastSpeedError = "no channels",
                    lastSpeedTestAtMs = System.currentTimeMillis(),
                )
            }
            return
        }

        var lastErr: String? = null
        for (ch in sampleChannels.take(MAX_CHANNEL_PROBES_PER_SERVER)) {
            coroutineContext.ensureActive()
            val streamUrl = runCatching {
                val direct = StalkerClient.stripCmdPrefix(ch.cmd)
                if (StalkerClient.isDirectPlayUrl(direct)) {
                    direct
                } else {
                    speedStalkerClient.createLink(
                        target.apiBase,
                        target.mac,
                        target.token,
                        ua,
                        ch.cmd,
                        type = "itv",
                    )
                }
            }.getOrElse { e ->
                if (e is CancellationException) throw e
                lastErr = e.message
                null
            } ?: continue

            val metric = measureStreamUrl(streamUrl, ua, depth = 0)
            if (metric != null) {
                store.updateServerSpeed(target.portalKey, persist = true) {
                    it.copy(
                        speedStatus = ServerSpeedStatus.OK,
                        ttfbMs = metric.ttfbMs,
                        downloadKbps = metric.kbps,
                        lastSpeedError = null,
                        lastSpeedTestAtMs = System.currentTimeMillis(),
                    )
                }
                return
            } else {
                lastErr = "stream read failed"
            }
        }

        store.updateServerSpeed(target.portalKey, persist = true) {
            it.copy(
                speedStatus = ServerSpeedStatus.OK,
                downloadKbps = null,
                ttfbMs = null,
                lastSpeedError = lastErr?.take(120),
                lastSpeedTestAtMs = System.currentTimeMillis(),
            )
        }
    }

    private data class StreamMetric(val ttfbMs: Long, val kbps: Double)

    private suspend fun measureStreamUrl(url: String, userAgent: String, depth: Int): StreamMetric? {
        if (depth > 2) return null
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .get()
            .build()

        val startNs = System.nanoTime()
        return runCatching {
            speedOkHttp.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body ?: return null
                val contentType = resp.header("Content-Type").orEmpty().lowercase()
                val looksHls = url.contains(".m3u8", ignoreCase = true) || contentType.contains("mpegurl")
                if (looksHls) {
                    val playlistText = body.string()
                    val nestedUrl = extractFirstPlaylistTarget(playlistText, resp.request.url.toString())
                        ?: return null
                    return measureStreamUrl(nestedUrl, userAgent, depth + 1)
                }

                readBurstMetrics(body.byteStream(), startNs)
            }
        }.getOrNull()
    }

    private suspend fun readBurstMetrics(stream: InputStream, requestStartNs: Long): StreamMetric? {
        val buf = ByteArray(8192)
        var firstByteNs = 0L
        var totalBytes = 0L
        val readStartWallNs = System.nanoTime()

        while (totalBytes < StalkerMacPoolDefaults.STREAM_READ_MAX_BYTES) {
            coroutineContext.ensureActive()
            val n = stream.read(buf)
            if (n < 0) break
            if (n == 0) continue
            if (firstByteNs == 0L) {
                firstByteNs = System.nanoTime()
            }
            totalBytes += n
            val elapsedSinceFirstMs = (System.nanoTime() - firstByteNs) / 1_000_000L
            if (elapsedSinceFirstMs >= StalkerMacPoolDefaults.STREAM_READ_MAX_MS) break
        }

        if (totalBytes <= 0L || firstByteNs == 0L) return null
        val ttfbMs = ((firstByteNs - requestStartNs) / 1_000_000L).coerceAtLeast(1L)
        val downloadDurationMs = ((System.nanoTime() - readStartWallNs) / 1_000_000L).coerceAtLeast(50L)
        val kbps = (totalBytes / 1024.0) / (downloadDurationMs / 1000.0)
        return StreamMetric(ttfbMs = ttfbMs, kbps = kbps)
    }

    private fun extractFirstPlaylistTarget(m3u8Text: String, baseUrl: String): String? {
        val parsedBase = baseUrl.toHttpUrlOrNull() ?: return null
        val lines = m3u8Text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }
        for (line in lines) {
            if (line.startsWith("#")) continue
            return parsedBase.resolve(line)?.toString()
        }
        return null
    }

    private suspend fun resetInProgressStatuses() {
        val snapshot = store.snapshot.value
        for (s in snapshot.servers) {
            if (s.speedStatus == ServerSpeedStatus.TESTING_PING || s.speedStatus == ServerSpeedStatus.TESTING_STREAM) {
                store.updateServerSpeed(s.portalKey, persist = false) {
                    it.copy(speedStatus = ServerSpeedStatus.UNTESTED)
                }
            }
        }
    }

    private companion object {
        const val MAX_MAC_PROBES_PER_SERVER = 3
        const val MAX_CHANNEL_PROBES_PER_SERVER = 3
    }
}
