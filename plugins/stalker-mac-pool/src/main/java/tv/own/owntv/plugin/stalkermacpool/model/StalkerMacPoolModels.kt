package tv.own.owntv.plugin.stalkermacpool.model

import tv.own.owntv.core.database.entity.SourceEntity

object StalkerMacPoolDefaults {
    val JSON_EXTENSIONS: Set<String> = setOf("json")
    const val SYNC_REASON: String = "stalker_mac_pool"
    const val DEFAULT_COOLDOWN_MINUTES: Int = 15
    const val SHORT_COOLDOWN_MS: Long = 5L * 60_000L
    const val AUTO_SWITCH_MAX_STREAK: Int = 3
    const val AUTO_SWITCH_DELAY_MS: Long = 600L
    const val PING_TIMEOUT_SEC: Long = 4L
    const val STREAM_READ_MAX_BYTES: Long = 512L * 1024L
    const val STREAM_READ_MAX_MS: Long = 2_500L
    const val LAN_PREFERRED_PORT: Int = 8899
}

enum class StalkerMacStatus {
    UNTESTED,
    OK,
    BUSY_COOLDOWN,
    AUTH_FAILED,
    EXPIRED,
}

enum class ServerSpeedStatus {
    UNTESTED,
    TESTING_PING,
    TESTING_STREAM,
    OK,
    UNREACHABLE,
}

enum class ServerSortMode {
    SPEED_DESC,
    PING_ASC,
    MAC_COUNT_DESC,
    CHANNELS_DESC,
}

enum class ImportMode {
    MERGE,
    REPLACE,
}

enum class PlaybackMediaKind {
    LIVE,
    MOVIE,
    SERIES,
}

data class StalkerMacEntry(
    val mac: String,
    val channels: Int = 0,
    val liveCategories: Int = 0,
    val movieCategories: Int = 0,
    val seriesCategories: Int = 0,
    val maxConnections: Int? = null,
    val expireDateRaw: String? = null,
    val expireEpochMs: Long? = null,
    val isUnlimited: Boolean = false,
    val isExpired: Boolean = false,
    val foundOn: String? = null,
    val proxyMeta: String? = null,
    val scannerSpeedKbps: Double = 0.0,
    val status: StalkerMacStatus = StalkerMacStatus.UNTESTED,
    val cooldownUntilMs: Long = 0L,
    val lastFailureReason: String? = null,
    val lastUsedAtMs: Long = 0L,
) {
    fun isInCooldown(nowMs: Long = System.currentTimeMillis()): Boolean =
        status == StalkerMacStatus.BUSY_COOLDOWN && cooldownUntilMs > nowMs

    fun isHealthyAt(nowMs: Long = System.currentTimeMillis()): Boolean =
        !isExpired &&
            status != StalkerMacStatus.AUTH_FAILED &&
            status != StalkerMacStatus.EXPIRED &&
            !isInCooldown(nowMs)
}

data class StalkerServerGroup(
    val portalKey: String,
    val rawPortalSamples: List<String> = emptyList(),
    val displayHost: String,
    val macs: List<StalkerMacEntry>,
    val speedStatus: ServerSpeedStatus = ServerSpeedStatus.UNTESTED,
    val pingMs: Long? = null,
    val ttfbMs: Long? = null,
    val downloadKbps: Double? = null,
    val lastSpeedTestAtMs: Long? = null,
    val lastSpeedError: String? = null,
    val workingMacHint: String? = null,
) {
    val totalMacs: Int get() = macs.size

    val maxChannels: Int get() = macs.maxOfOrNull { it.channels } ?: 0

    fun healthyMacCount(nowMs: Long = System.currentTimeMillis()): Int =
        macs.count { it.isHealthyAt(nowMs) }
}

data class MacPoolSettings(
    val autoSwitchOnConflict: Boolean = false,
    val sortMode: ServerSortMode = ServerSortMode.SPEED_DESC,
    val cooldownMinutes: Int = StalkerMacPoolDefaults.DEFAULT_COOLDOWN_MINUTES,
)

data class MacPoolSnapshot(
    val servers: List<StalkerServerGroup> = emptyList(),
    val settings: MacPoolSettings = MacPoolSettings(),
    val loaded: Boolean = false,
) {
    val totalMacCount: Int get() = servers.sumOf { it.macs.size }
}

data class ImportSummary(
    val totalParsed: Int,
    val uniqueRecords: Int,
    val serverCount: Int,
    val addedCount: Int,
    val updatedCount: Int,
    val skippedInvalid: Int,
)

data class ActivePlaybackContext(
    val sourceId: Long,
    val channelId: Long? = null,
    val mediaKind: PlaybackMediaKind = PlaybackMediaKind.LIVE,
    val reopenAfterMacSwitch: () -> Unit,
)

interface StalkerMacPoolHostCallbacks {
    fun showToast(message: String)
    fun enqueueSourceSync(source: SourceEntity)
}
