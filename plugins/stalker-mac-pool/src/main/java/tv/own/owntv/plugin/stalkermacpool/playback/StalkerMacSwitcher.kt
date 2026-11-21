package tv.own.owntv.plugin.stalkermacpool.playback

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import tv.own.owntv.core.database.dao.ChannelDao
import tv.own.owntv.core.database.dao.ProfileDao
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.database.dao.resolveExistingProfileId
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.model.SourceType
import tv.own.owntv.core.repository.SourceRepository
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.core.stalker.StalkerAuthManager
import tv.own.owntv.core.stalker.StalkerClient
import tv.own.owntv.player.ErrorInfo
import tv.own.owntv.player.PlaybackFailure
import tv.own.owntv.plugin.stalkermacpool.R
import tv.own.owntv.plugin.stalkermacpool.data.StalkerMacPoolStore
import tv.own.owntv.plugin.stalkermacpool.model.ActivePlaybackContext
import tv.own.owntv.plugin.stalkermacpool.model.StalkerMacEntry
import tv.own.owntv.plugin.stalkermacpool.model.StalkerMacPoolDefaults
import tv.own.owntv.plugin.stalkermacpool.model.StalkerMacPoolHostCallbacks
import tv.own.owntv.plugin.stalkermacpool.model.StalkerMacStatus
import tv.own.owntv.plugin.stalkermacpool.model.StalkerServerGroup
import tv.own.owntv.plugin.stalkermacpool.parser.StalkerJsonImporter
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class StalkerMacSwitcher(
    private val appContext: Context,
    private val store: StalkerMacPoolStore,
    private val sourceDao: SourceDao,
    private val channelDao: ChannelDao,
    private val profileDao: ProfileDao,
    private val sourceRepository: SourceRepository,
    private val settingsRepository: SettingsRepository,
    private val stalkerAuth: StalkerAuthManager,
    baseOkHttpClient: OkHttpClient,
    private val hostCallbacks: StalkerMacPoolHostCallbacks,
) {
    data class SourcePoolMatch(
        val source: SourceEntity,
        val server: StalkerServerGroup,
        val activeMac: String?,
        val alternateCount: Int,
        val healthyAlternateCount: Int,
    )

    data class ActivationResult(
        val source: SourceEntity,
        val mac: String,
        val createdNewSource: Boolean,
    )

    sealed interface SwitchResult {
        data class Switched(val newMac: String, val indexInPool: Int, val totalInPool: Int) : SwitchResult
        data object NoCandidate : SwitchResult
        data class Failed(val reason: String) : SwitchResult
    }

    private val switchMutex = Mutex()

    private val verifyClient = StalkerClient(
        baseOkHttpClient.newBuilder()
            .connectTimeout(StalkerMacPoolDefaults.PING_TIMEOUT_SEC, TimeUnit.SECONDS)
            .readTimeout(StalkerMacPoolDefaults.PING_TIMEOUT_SEC, TimeUnit.SECONDS)
            .writeTimeout(StalkerMacPoolDefaults.PING_TIMEOUT_SEC, TimeUnit.SECONDS)
            .build(),
    )

    fun observeActiveStalkerSources(): Flow<Map<String, SourceEntity>> =
        settingsRepository.activeProfileId
            .flatMapLatest { preferredId ->
                if (preferredId < 0L) {
                    flowOf(emptyList())
                } else {
                    sourceDao.observeForProfile(preferredId)
                }
            }
            .map { list ->
                list.filter { it.type == SourceType.STALKER }
                    .mapNotNull { s ->
                        val key = StalkerJsonImporter.normalizePortalUrl(s.url) ?: return@mapNotNull null
                        key to s
                    }
                    .toMap()
            }
            .distinctUntilChanged()

    fun observeSourcePoolStatus(sourceId: Long): Flow<SourcePoolMatch?> =
        combine(
            sourceDao.observeAll().map { all -> all.firstOrNull { it.id == sourceId } },
            store.snapshot,
        ) { source, snap ->
            if (source == null || source.type != SourceType.STALKER) return@combine null
            val key = StalkerJsonImporter.normalizePortalUrl(source.url) ?: return@combine null
            val server = snap.servers.firstOrNull { it.portalKey == key } ?: return@combine null
            val activeCanonical = source.mac?.let { StalkerJsonImporter.canonicalizeMac(it) ?: it }
            val alts = server.macs.filter { it.mac != activeCanonical }
            val now = System.currentTimeMillis()
            SourcePoolMatch(
                source = source,
                server = server,
                activeMac = activeCanonical,
                alternateCount = alts.size,
                healthyAlternateCount = alts.count { it.isHealthyAt(now) },
            )
        }

    suspend fun activateServerAsSource(
        portalKey: String,
        preferredMac: String? = null,
    ): Result<ActivationResult> = switchMutex.withLock {
        runCatching {
            val snap = store.ensureLoaded()
            val server = snap.servers.firstOrNull { it.portalKey == portalKey }
                ?: error("Server not found")

            val now = System.currentTimeMillis()
            val chosenMac = preferredMac
                ?.let { StalkerJsonImporter.canonicalizeMac(it) ?: it }
                ?: server.workingMacHint?.takeIf { hint -> server.macs.any { it.mac == hint && it.isHealthyAt(now) } }
                ?: StalkerMacRotationPolicy.rankCandidates(server.macs, currentMac = null, nowMs = now, includeFallbackInvalid = true)
                    .firstOrNull()?.mac
                ?: error("No MAC entries in server group")

            val preferredProfileId = settingsRepository.activeProfileIdNow()
            val profileId = withContext(Dispatchers.IO) {
                profileDao.resolveExistingProfileId(preferredProfileId)
            } ?: error("No active profile")

            val existingSources = sourceDao.observeForProfile(profileId).first()
            val existing = existingSources.firstOrNull {
                it.type == SourceType.STALKER &&
                    StalkerJsonImporter.normalizePortalUrl(it.url) == portalKey
            }

            val resultSource: SourceEntity
            val createdNew: Boolean
            if (existing != null) {
                val updated = existing.copy(mac = chosenMac)
                withContext(Dispatchers.IO) { sourceDao.update(updated) }
                stalkerAuth.invalidate(updated.id)
                resultSource = updated
                createdNew = false
            } else {
                resultSource = withContext(Dispatchers.IO) {
                    sourceRepository.addStalkerSource(
                        profileId = profileId,
                        name = server.displayHost,
                        portalUrl = server.portalKey,
                        mac = chosenMac,
                    )
                }
                createdNew = true
            }

            store.updateMacState(
                portalKey = portalKey,
                mac = chosenMac,
                status = StalkerMacStatus.OK,
                cooldownUntilMs = 0L,
                failureReason = null,
                markUsed = true,
            )
            hostCallbacks.enqueueSourceSync(resultSource)
            hostCallbacks.showToast(
                appContext.getString(R.string.smp_source_activated, server.displayHost, chosenMac),
            )
            ActivationResult(source = resultSource, mac = chosenMac, createdNewSource = createdNew)
        }.onFailure { err ->
            if (err is CancellationException) throw err
            hostCallbacks.showToast(
                appContext.getString(R.string.smp_source_activate_failed, err.message ?: ""),
            )
        }
    }

    suspend fun switchMacForPlayback(
        context: ActivePlaybackContext,
        targetMac: String? = null,
        failure: PlaybackFailure? = null,
        errorInfo: ErrorInfo? = null,
    ): SwitchResult = switchMutex.withLock {
        val snap = store.ensureLoaded()
        val source = withContext(Dispatchers.IO) { sourceDao.getById(context.sourceId) }
            ?: return@withLock SwitchResult.Failed("Source not found")
        if (source.type != SourceType.STALKER) {
            return@withLock SwitchResult.Failed("Not a Stalker source")
        }
        val portalKey = StalkerJsonImporter.normalizePortalUrl(source.url)
            ?: return@withLock SwitchResult.Failed("Invalid portal URL")
        val server = snap.servers.firstOrNull { it.portalKey == portalKey }
            ?: return@withLock SwitchResult.NoCandidate

        val now = System.currentTimeMillis()
        val currentMac = source.mac?.let { StalkerJsonImporter.canonicalizeMac(it) ?: it }

        // Only penalize the previous MAC when switching in response to a failure.
        if (failure != null && currentMac != null && targetMac == null) {
            val kind = StalkerMacRotationPolicy.classifyFailure(failure, errorInfo)
            val reasonText = errorInfo?.raw?.take(120) ?: failure.toString()
            when (kind) {
                StalkerMacRotationPolicy.FailureKind.CONFLICT_BUSY -> {
                    val cooldownMs = snap.settings.cooldownMinutes.coerceAtLeast(1) * 60_000L
                    store.updateMacState(
                        portalKey = portalKey,
                        mac = currentMac,
                        status = StalkerMacStatus.BUSY_COOLDOWN,
                        cooldownUntilMs = now + cooldownMs,
                        failureReason = reasonText,
                    )
                }
                StalkerMacRotationPolicy.FailureKind.AUTH_INVALID -> {
                    store.updateMacState(
                        portalKey = portalKey,
                        mac = currentMac,
                        status = StalkerMacStatus.AUTH_FAILED,
                        cooldownUntilMs = 0L,
                        failureReason = reasonText,
                    )
                }
                StalkerMacRotationPolicy.FailureKind.GENERIC_STREAM -> {
                    store.updateMacState(
                        portalKey = portalKey,
                        mac = currentMac,
                        status = StalkerMacStatus.BUSY_COOLDOWN,
                        cooldownUntilMs = now + StalkerMacPoolDefaults.SHORT_COOLDOWN_MS,
                        failureReason = reasonText,
                    )
                }
                StalkerMacRotationPolicy.FailureKind.NON_RECOVERABLE -> Unit
            }
        }

        val candidates: List<StalkerMacEntry> = if (targetMac != null) {
            val targetCanonical = StalkerJsonImporter.canonicalizeMac(targetMac) ?: targetMac
            val found = server.macs.firstOrNull { it.mac == targetCanonical }
                ?: StalkerMacEntry(mac = targetCanonical)
            listOf(found)
        } else {
            val freshGroup = store.snapshot.value.servers.firstOrNull { it.portalKey == portalKey } ?: server
            StalkerMacRotationPolicy.rankCandidates(
                macs = freshGroup.macs,
                currentMac = currentMac,
                nowMs = now,
                includeFallbackInvalid = false,
            )
        }

        if (candidates.isEmpty()) {
            hostCallbacks.showToast(appContext.getString(R.string.smp_switch_no_candidate))
            return@withLock SwitchResult.NoCandidate
        }

        val chosen = if (targetMac != null) {
            candidates.first()
        } else {
            val verified = pickVerifiedCandidate(portalKey, source, candidates)
            if (verified != null) {
                verified
            } else {
                val postVerifyGroup = store.snapshot.value.servers.firstOrNull { it.portalKey == portalKey } ?: server
                val fallback = StalkerMacRotationPolicy.rankCandidates(
                    macs = postVerifyGroup.macs,
                    currentMac = currentMac,
                    nowMs = now,
                    includeFallbackInvalid = false,
                ).firstOrNull()
                if (fallback == null) {
                    hostCallbacks.showToast(appContext.getString(R.string.smp_switch_no_candidate))
                    return@withLock SwitchResult.NoCandidate
                }
                fallback
            }
        }

        val updatedSource = source.copy(mac = chosen.mac)
        withContext(Dispatchers.IO) {
            sourceDao.update(updatedSource)
        }
        stalkerAuth.invalidate(source.id)

        store.updateMacState(
            portalKey = portalKey,
            mac = chosen.mac,
            status = StalkerMacStatus.OK,
            cooldownUntilMs = 0L,
            failureReason = null,
            markUsed = true,
        )

        if (context.channelId != null) {
            runCatching {
                refreshDirectPlayChannelIfNeeded(updatedSource, context.channelId, chosen.mac)
            }
        }

        withContext(Dispatchers.Main) {
            context.reopenAfterMacSwitch()
        }

        val latestGroup = store.snapshot.value.servers.firstOrNull { it.portalKey == portalKey } ?: server
        val idx = (latestGroup.macs.indexOfFirst { it.mac == chosen.mac } + 1).coerceAtLeast(1)
        hostCallbacks.showToast(
            appContext.getString(R.string.smp_switched_mac_toast, chosen.mac, idx, latestGroup.totalMacs.coerceAtLeast(1)),
        )
        SwitchResult.Switched(newMac = chosen.mac, indexInPool = idx, totalInPool = latestGroup.totalMacs)
    }

    private suspend fun pickVerifiedCandidate(
        portalKey: String,
        source: SourceEntity,
        candidates: List<StalkerMacEntry>,
    ): StalkerMacEntry? = withContext(Dispatchers.IO) {
        val ua = source.userAgent ?: StalkerClient.DEFAULT_MAG_USER_AGENT
        for (candidate in candidates.take(StalkerMacPoolDefaults.AUTO_SWITCH_MAX_STREAK)) {
            try {
                val hs = verifyClient.resolveHandshake(portalKey, candidate.mac, ua)
                verifyClient.getProfile(hs.apiBase, candidate.mac, hs.token, ua)
                return@withContext candidate
            } catch (c: CancellationException) {
                throw c
            } catch (auth: StalkerClient.StalkerAuthException) {
                store.updateMacState(
                    portalKey = portalKey,
                    mac = candidate.mac,
                    status = StalkerMacStatus.AUTH_FAILED,
                    cooldownUntilMs = 0L,
                    failureReason = auth.message,
                )
            } catch (_: Exception) {
                // Network glitch or timeout: avoid prematurely marking AUTH_FAILED and let player try next or fallback.
            }
        }
        null
    }

    /**
     * Direct-play Stalker portals embed `mac=` / `play_token=` right inside `channel.streamUrl`
     * instead of going through `create_link`. When switching MACs cleanly without touching `:core`,
     * update those URLs for the active channel (and token-based siblings if needed).
     */
    private suspend fun refreshDirectPlayChannelIfNeeded(
        updatedSource: SourceEntity,
        channelId: Long,
        newMac: String,
    ) = withContext(Dispatchers.IO) {
        val channel: ChannelEntity = channelDao.getById(channelId) ?: return@withContext
        val stripped = StalkerClient.stripCmdPrefix(channel.streamUrl)
        if (!StalkerClient.isDirectPlayUrl(stripped)) return@withContext

        val hasToken = StalkerClient.hasPlayToken(channel.streamUrl)
        if (!hasToken) {
            val rewritten = rewriteMacQueryParam(channel.streamUrl, newMac)
            if (rewritten != channel.streamUrl) {
                channelDao.updateAll(listOf(channel.copy(streamUrl = rewritten)))
            }
            return@withContext
        }

        // Token-bearing direct-play portal: ask portal for fresh cmd list using the new MAC.
        val ua = updatedSource.userAgent ?: StalkerClient.DEFAULT_MAG_USER_AGENT
        val hs = runCatching { verifyClient.resolveHandshake(updatedSource.url, newMac, ua) }.getOrNull() ?: return@withContext
        val freshList = runCatching {
            verifyClient.getAllChannels(hs.apiBase, newMac, hs.token, ua)
        }.getOrNull().orEmpty()

        val remoteId = channel.remoteId
        val match = if (remoteId != null) {
            freshList.firstOrNull { it.id == remoteId }
        } else {
            freshList.firstOrNull { it.name.equals(channel.name, ignoreCase = true) }
        }

        if (match != null && match.cmd.isNotBlank()) {
            channelDao.updateAll(listOf(channel.copy(streamUrl = match.cmd)))
        } else {
            val fallback = rewriteMacQueryParam(channel.streamUrl, newMac)
            if (fallback != channel.streamUrl) {
                channelDao.updateAll(listOf(channel.copy(streamUrl = fallback)))
            }
        }
    }

    private fun rewriteMacQueryParam(cmd: String, newMac: String): String {
        val trimmed = cmd.trim()
        val prefixes = listOf("ffmpeg ", "ffrt2 ", "ffrt3 ", "ffrt ", "auto ")
        val prefix = prefixes.firstOrNull { trimmed.startsWith(it, ignoreCase = true) } ?: ""
        val rawUrl = trimmed.removePrefix(prefix).trim()
        val parsed = rawUrl.toHttpUrlOrNull() ?: return cmd
        if (parsed.queryParameter("mac") == null) return cmd
        val updatedUrl = parsed.newBuilder().setQueryParameter("mac", newMac).build().toString()
        return if (prefix.isNotEmpty()) "$prefix$updatedUrl" else updatedUrl
    }
}
