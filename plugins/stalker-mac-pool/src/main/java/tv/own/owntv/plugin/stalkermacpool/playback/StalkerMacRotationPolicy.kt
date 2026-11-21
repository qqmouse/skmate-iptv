package tv.own.owntv.plugin.stalkermacpool.playback

import tv.own.owntv.player.ErrorInfo
import tv.own.owntv.player.PlaybackFailure
import tv.own.owntv.plugin.stalkermacpool.model.StalkerMacEntry
import tv.own.owntv.plugin.stalkermacpool.model.StalkerMacStatus
import tv.own.owntv.plugin.stalkermacpool.parser.StalkerJsonImporter

/**
 * Pure rotation and error classification rules shared by speed tester, switcher, and HUD.
 */
object StalkerMacRotationPolicy {

    enum class FailureKind {
        CONFLICT_BUSY,
        AUTH_INVALID,
        GENERIC_STREAM,
        NON_RECOVERABLE,
    }

    private val conflictTokens = listOf(
        "458",
        "403",
        "429",
        "too many connections",
        "already streaming",
        "connection limit",
        "max connection",
        "account is in use",
        "limit reached",
    )

    private val authTokens = listOf(
        "401",
        "unauthorized",
        "authorization failed",
        "not authorized",
        "account expired",
        "blocked",
        "handshake",
        "create_link",
    )

    fun classifyFailure(failure: PlaybackFailure?, errorInfo: ErrorInfo?): FailureKind {
        if (failure == PlaybackFailure.AccountInUse) return FailureKind.CONFLICT_BUSY
        when (failure) {
            PlaybackFailure.NoInternet,
            PlaybackFailure.DecoderBusy,
            PlaybackFailure.DecoderExhausted,
            PlaybackFailure.FileCorrupt,
            PlaybackFailure.ImageFormat,
            PlaybackFailure.ImageShow,
            PlaybackFailure.ImageSubtitleAudio,
            PlaybackFailure.CastUnsupported,
            PlaybackFailure.Surround,
            is PlaybackFailure.HardwareDisabled,
            is PlaybackFailure.HardwareFormat,
            is PlaybackFailure.ExoDecode,
            -> return FailureKind.NON_RECOVERABLE
            else -> Unit
        }

        val combinedRaw = buildString {
            (failure as? PlaybackFailure.Raw)?.message?.let { append(it).append(' ') }
            errorInfo?.raw?.let { append(it).append(' ') }
            if (failure is PlaybackFailure.BothEnginesMpvFirst) {
                (failure.exoError as? PlaybackFailure.Raw)?.message?.let { append(it).append(' ') }
                if (failure.exoError == PlaybackFailure.AccountInUse) return FailureKind.CONFLICT_BUSY
            }
        }.lowercase()

        if (conflictTokens.any { combinedRaw.contains(it) }) return FailureKind.CONFLICT_BUSY
        if (authTokens.any { combinedRaw.contains(it) }) return FailureKind.AUTH_INVALID
        return FailureKind.GENERIC_STREAM
    }

    fun isConflictForAutoSwitch(failure: PlaybackFailure?, errorInfo: ErrorInfo?): Boolean =
        classifyFailure(failure, errorInfo) == FailureKind.CONFLICT_BUSY

    fun rankCandidates(
        macs: List<StalkerMacEntry>,
        currentMac: String?,
        nowMs: Long = System.currentTimeMillis(),
        includeFallbackInvalid: Boolean = false,
    ): List<StalkerMacEntry> {
        val currentCanonical = currentMac?.let { StalkerJsonImporter.canonicalizeMac(it) ?: it.uppercase() }
        val candidates = macs.filter { entry ->
            entry.mac != currentCanonical && (includeFallbackInvalid || entry.isHealthyAt(nowMs) || entry.isInCooldown(nowMs))
        }
        return candidates.sortedWith(
            compareBy<StalkerMacEntry> { candidateBucket(it, nowMs) }
                .thenBy { if (it.isInCooldown(nowMs)) it.cooldownUntilMs else 0L }
                .thenByDescending { it.channels }
                .thenByDescending { it.maxConnections ?: 0 }
                .thenByDescending { if (it.isUnlimited) 1 else 0 }
                .thenByDescending { it.expireEpochMs ?: Long.MAX_VALUE }
                .thenBy { it.lastUsedAtMs }
                .thenBy { it.mac },
        )
    }

    private fun candidateBucket(entry: StalkerMacEntry, nowMs: Long): Int = when {
        entry.isHealthyAt(nowMs) -> 0
        entry.isInCooldown(nowMs) -> 1
        entry.status == StalkerMacStatus.EXPIRED || entry.isExpired -> 3
        else -> 2
    }
}
