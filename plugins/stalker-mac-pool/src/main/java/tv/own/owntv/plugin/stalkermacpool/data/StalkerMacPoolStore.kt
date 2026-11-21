package tv.own.owntv.plugin.stalkermacpool.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import tv.own.owntv.plugin.stalkermacpool.model.ImportMode
import tv.own.owntv.plugin.stalkermacpool.model.ImportSummary
import tv.own.owntv.plugin.stalkermacpool.model.MacPoolSettings
import tv.own.owntv.plugin.stalkermacpool.model.MacPoolSnapshot
import tv.own.owntv.plugin.stalkermacpool.model.ServerSortMode
import tv.own.owntv.plugin.stalkermacpool.model.ServerSpeedStatus
import tv.own.owntv.plugin.stalkermacpool.model.StalkerMacEntry
import tv.own.owntv.plugin.stalkermacpool.model.StalkerMacPoolDefaults
import tv.own.owntv.plugin.stalkermacpool.model.StalkerMacStatus
import tv.own.owntv.plugin.stalkermacpool.model.StalkerServerGroup
import tv.own.owntv.plugin.stalkermacpool.parser.StalkerJsonImporter
import java.io.File

/**
 * Self-contained atomic persistence for the Stalker MAC Pool plugin.
 * Stored under `filesDir/plugins/stalker_mac_pool/` so core Room DB migrations stay untouched.
 */
class StalkerMacPoolStore(context: Context) {

    private val dir = File(context.filesDir, "plugins/stalker_mac_pool")
    private val stateFile = File(dir, "pool_state.json")
    private val mutex = Mutex()

    private val _snapshot = MutableStateFlow(MacPoolSnapshot())
    val snapshot: StateFlow<MacPoolSnapshot> = _snapshot.asStateFlow()

    suspend fun ensureLoaded(): MacPoolSnapshot {
        if (_snapshot.value.loaded) return _snapshot.value
        return mutex.withLock {
            if (_snapshot.value.loaded) return@withLock _snapshot.value
            val loaded = withContext(Dispatchers.IO) { readFromDisk() }
            _snapshot.value = loaded
            loaded
        }
    }

    suspend fun importFile(file: File, mode: ImportMode): ImportSummary {
        val text = withContext(Dispatchers.IO) { file.readText(Charsets.UTF_8) }
        return importJson(text, mode)
    }

    suspend fun importJson(jsonText: String, mode: ImportMode): ImportSummary {
        ensureLoaded()
        val parsed = withContext(Dispatchers.Default) { StalkerJsonImporter.parse(jsonText) }
        return mutex.withLock {
            val current = _snapshot.value
            var addedCount = 0
            var updatedCount = 0

            val nextServers: List<StalkerServerGroup> = when (mode) {
                ImportMode.REPLACE -> {
                    addedCount = parsed.uniqueRecords
                    parsed.groups
                }
                ImportMode.MERGE -> {
                    val map = current.servers.associateByTo(linkedMapOf()) { it.portalKey }
                    for (incomingGroup in parsed.groups) {
                        val existingGroup = map[incomingGroup.portalKey]
                        if (existingGroup == null) {
                            addedCount += incomingGroup.macs.size
                            map[incomingGroup.portalKey] = incomingGroup
                        } else {
                            val macMap = existingGroup.macs.associateByTo(linkedMapOf()) { it.mac }
                            for (incomingMac in incomingGroup.macs) {
                                val prevMac = macMap[incomingMac.mac]
                                if (prevMac == null) {
                                    addedCount++
                                    macMap[incomingMac.mac] = incomingMac
                                } else {
                                    updatedCount++
                                    macMap[incomingMac.mac] = StalkerJsonImporter.mergeMacEntry(
                                        prevMac,
                                        incomingMac,
                                        preserveRuntimeState = true,
                                    )
                                }
                            }
                            val mergedSamples = (existingGroup.rawPortalSamples + incomingGroup.rawPortalSamples).distinct()
                            val sortedMacs = macMap.values.sortedWith(
                                compareByDescending<StalkerMacEntry> { it.channels }
                                    .thenByDescending { it.maxConnections ?: 0 }
                                    .thenBy { it.mac },
                            )
                            map[incomingGroup.portalKey] = existingGroup.copy(
                                rawPortalSamples = mergedSamples,
                                macs = sortedMacs,
                            )
                        }
                    }
                    map.values.toList()
                }
            }

            val updatedSnapshot = current.copy(servers = nextServers, loaded = true)
            _snapshot.value = updatedSnapshot
            withContext(Dispatchers.IO) { writeToDisk(updatedSnapshot) }

            ImportSummary(
                totalParsed = parsed.totalParsed,
                uniqueRecords = parsed.uniqueRecords,
                serverCount = parsed.groups.size,
                addedCount = addedCount,
                updatedCount = updatedCount,
                skippedInvalid = parsed.skippedInvalid,
            )
        }
    }

    suspend fun deleteServer(portalKey: String) {
        ensureLoaded()
        mutateAndPersist { cur ->
            cur.copy(servers = cur.servers.filterNot { it.portalKey == portalKey })
        }
    }

    suspend fun clearAll() {
        ensureLoaded()
        mutateAndPersist { cur ->
            cur.copy(servers = emptyList())
        }
    }

    suspend fun updateSettings(transform: (MacPoolSettings) -> MacPoolSettings) {
        ensureLoaded()
        mutateAndPersist { cur ->
            cur.copy(settings = transform(cur.settings))
        }
    }

    suspend fun updateServerSpeed(
        portalKey: String,
        persist: Boolean = true,
        transform: (StalkerServerGroup) -> StalkerServerGroup,
    ) {
        ensureLoaded()
        mutex.withLock {
            val cur = _snapshot.value
            val updated = cur.copy(
                servers = cur.servers.map { g ->
                    if (g.portalKey == portalKey) transform(g) else g
                },
            )
            _snapshot.value = updated
            if (persist) withContext(Dispatchers.IO) { writeToDisk(updated) }
        }
    }

    suspend fun updateMacState(
        portalKey: String,
        mac: String,
        status: StalkerMacStatus,
        cooldownUntilMs: Long = 0L,
        failureReason: String? = null,
        markUsed: Boolean = false,
    ) {
        ensureLoaded()
        val canonical = StalkerJsonImporter.canonicalizeMac(mac) ?: mac
        val now = System.currentTimeMillis()
        mutateAndPersist { cur ->
            cur.copy(
                servers = cur.servers.map { group ->
                    if (group.portalKey != portalKey) {
                        group
                    } else {
                        group.copy(
                            macs = group.macs.map { entry ->
                                if (entry.mac == canonical) {
                                    entry.copy(
                                        status = status,
                                        cooldownUntilMs = cooldownUntilMs,
                                        lastFailureReason = failureReason,
                                        lastUsedAtMs = if (markUsed) now else entry.lastUsedAtMs,
                                    )
                                } else {
                                    entry
                                }
                            },
                        )
                    }
                },
            )
        }
    }

    fun findServerForPortal(portalUrl: String?): StalkerServerGroup? {
        val key = StalkerJsonImporter.normalizePortalUrl(portalUrl) ?: return null
        return _snapshot.value.servers.firstOrNull { it.portalKey == key }
    }

    fun sortedServers(snapshot: MacPoolSnapshot = _snapshot.value): List<StalkerServerGroup> {
        val now = System.currentTimeMillis()
        return sortServerList(snapshot.servers, snapshot.settings.sortMode, now)
    }

    private suspend fun mutateAndPersist(transform: (MacPoolSnapshot) -> MacPoolSnapshot) {
        mutex.withLock {
            val updated = transform(_snapshot.value).copy(loaded = true)
            _snapshot.value = updated
            withContext(Dispatchers.IO) { writeToDisk(updated) }
        }
    }

    private fun readFromDisk(): MacPoolSnapshot {
        if (!stateFile.isFile) return MacPoolSnapshot(loaded = true)
        return runCatching {
            val root = JSONObject(stateFile.readText(Charsets.UTF_8))
            val settingsObj = root.optJSONObject("settings")
            val sortName = settingsObj?.optString("sort_mode", ServerSortMode.SPEED_DESC.name)
            val sortMode = runCatching {
                ServerSortMode.valueOf(sortName ?: ServerSortMode.SPEED_DESC.name)
            }.getOrDefault(ServerSortMode.SPEED_DESC)
            val settings = MacPoolSettings(
                autoSwitchOnConflict = settingsObj?.optBoolean("auto_switch_on_conflict", false) ?: false,
                sortMode = sortMode,
                cooldownMinutes = (settingsObj?.optInt("cooldown_minutes", StalkerMacPoolDefaults.DEFAULT_COOLDOWN_MINUTES)
                    ?: StalkerMacPoolDefaults.DEFAULT_COOLDOWN_MINUTES).coerceIn(1, 180),
            )
            val serversArr = root.optJSONArray("servers") ?: JSONArray()
            val servers = ArrayList<StalkerServerGroup>(serversArr.length())
            val now = System.currentTimeMillis()
            for (i in 0 until serversArr.length()) {
                val obj = serversArr.optJSONObject(i) ?: continue
                val portalKey = obj.optString("portal_key", "").takeIf { it.isNotBlank() } ?: continue
                val displayHost = obj.optString("display_host", "").ifBlank {
                    StalkerJsonImporter.displayHostOf(portalKey)
                }
                val samplesArr = obj.optJSONArray("raw_portal_samples")
                val samples = buildList {
                    if (samplesArr != null) {
                        for (j in 0 until samplesArr.length()) {
                            samplesArr.optString(j).takeIf { it.isNotBlank() }?.let(::add)
                        }
                    }
                }
                val statusName = obj.optString("speed_status", ServerSpeedStatus.UNTESTED.name)
                val persistedStatus = runCatching { ServerSpeedStatus.valueOf(statusName) }
                    .getOrDefault(ServerSpeedStatus.UNTESTED)
                val safeSpeedStatus = when (persistedStatus) {
                    ServerSpeedStatus.TESTING_PING, ServerSpeedStatus.TESTING_STREAM -> ServerSpeedStatus.UNTESTED
                    else -> persistedStatus
                }
                val macsArr = obj.optJSONArray("macs") ?: JSONArray()
                val macs = ArrayList<StalkerMacEntry>(macsArr.length())
                for (j in 0 until macsArr.length()) {
                    val mObj = macsArr.optJSONObject(j) ?: continue
                    val mac = StalkerJsonImporter.canonicalizeMac(mObj.optString("mac", "")) ?: continue
                    val mStatus = runCatching {
                        StalkerMacStatus.valueOf(mObj.optString("status", StalkerMacStatus.UNTESTED.name))
                    }.getOrDefault(StalkerMacStatus.UNTESTED)
                    val expEpoch = if (mObj.has("expire_epoch_ms") && !mObj.isNull("expire_epoch_ms")) {
                        mObj.optLong("expire_epoch_ms")
                    } else null
                    val unlimited = mObj.optBoolean("is_unlimited", false)
                    val expired = mObj.optBoolean("is_expired", false) ||
                        (!unlimited && expEpoch != null && expEpoch < now)
                    macs.add(
                        StalkerMacEntry(
                            mac = mac,
                            channels = mObj.optInt("channels", 0),
                            liveCategories = mObj.optInt("live_categories", 0),
                            movieCategories = mObj.optInt("movie_categories", 0),
                            seriesCategories = mObj.optInt("series_categories", 0),
                            maxConnections = if (mObj.has("max_connections") && !mObj.isNull("max_connections")) {
                                mObj.optInt("max_connections").takeIf { it > 0 }
                            } else null,
                            expireDateRaw = mObj.optString("expire_date_raw", "").takeIf { it.isNotEmpty() },
                            expireEpochMs = expEpoch,
                            isUnlimited = unlimited,
                            isExpired = expired,
                            foundOn = mObj.optString("found_on", "").takeIf { it.isNotEmpty() },
                            proxyMeta = mObj.optString("proxy_meta", "").takeIf { it.isNotEmpty() },
                            scannerSpeedKbps = mObj.optDouble("scanner_speed_kbps", 0.0),
                            status = if (expired) StalkerMacStatus.EXPIRED else mStatus,
                            cooldownUntilMs = mObj.optLong("cooldown_until_ms", 0L),
                            lastFailureReason = mObj.optString("last_failure_reason", "").takeIf { it.isNotEmpty() },
                            lastUsedAtMs = mObj.optLong("last_used_at_ms", 0L),
                        ),
                    )
                }
                servers.add(
                    StalkerServerGroup(
                        portalKey = portalKey,
                        rawPortalSamples = samples,
                        displayHost = displayHost,
                        macs = macs,
                        speedStatus = safeSpeedStatus,
                        pingMs = if (obj.has("ping_ms") && !obj.isNull("ping_ms")) obj.optLong("ping_ms") else null,
                        ttfbMs = if (obj.has("ttfb_ms") && !obj.isNull("ttfb_ms")) obj.optLong("ttfb_ms") else null,
                        downloadKbps = if (obj.has("download_kbps") && !obj.isNull("download_kbps")) {
                            obj.optDouble("download_kbps").takeIf { it.isFinite() }
                        } else null,
                        lastSpeedTestAtMs = if (obj.has("last_speed_test_at_ms") && !obj.isNull("last_speed_test_at_ms")) {
                            obj.optLong("last_speed_test_at_ms")
                        } else null,
                        lastSpeedError = obj.optString("last_speed_error", "").takeIf { it.isNotEmpty() },
                        workingMacHint = obj.optString("working_mac_hint", "").takeIf { it.isNotEmpty() },
                    ),
                )
            }
            MacPoolSnapshot(servers = servers, settings = settings, loaded = true)
        }.getOrElse {
            MacPoolSnapshot(loaded = true)
        }
    }

    private fun writeToDisk(snapshot: MacPoolSnapshot) {
        if (!dir.exists()) dir.mkdirs()
        val root = JSONObject()
        root.put("version", 1)
        root.put(
            "settings",
            JSONObject().apply {
                put("auto_switch_on_conflict", snapshot.settings.autoSwitchOnConflict)
                put("sort_mode", snapshot.settings.sortMode.name)
                put("cooldown_minutes", snapshot.settings.cooldownMinutes)
            },
        )
        val serversArr = JSONArray()
        for (group in snapshot.servers) {
            val sObj = JSONObject().apply {
                put("portal_key", group.portalKey)
                put("display_host", group.displayHost)
                put("raw_portal_samples", JSONArray(group.rawPortalSamples))
                put("speed_status", group.speedStatus.name)
                put("ping_ms", group.pingMs)
                put("ttfb_ms", group.ttfbMs)
                put("download_kbps", group.downloadKbps)
                put("last_speed_test_at_ms", group.lastSpeedTestAtMs)
                put("last_speed_error", group.lastSpeedError)
                put("working_mac_hint", group.workingMacHint)
                val macsArr = JSONArray()
                for (m in group.macs) {
                    macsArr.put(
                        JSONObject().apply {
                            put("mac", m.mac)
                            put("channels", m.channels)
                            put("live_categories", m.liveCategories)
                            put("movie_categories", m.movieCategories)
                            put("series_categories", m.seriesCategories)
                            put("max_connections", m.maxConnections)
                            put("expire_date_raw", m.expireDateRaw)
                            put("expire_epoch_ms", m.expireEpochMs)
                            put("is_unlimited", m.isUnlimited)
                            put("is_expired", m.isExpired)
                            put("found_on", m.foundOn)
                            put("proxy_meta", m.proxyMeta)
                            put("scanner_speed_kbps", m.scannerSpeedKbps)
                            put("status", m.status.name)
                            put("cooldown_until_ms", m.cooldownUntilMs)
                            put("last_failure_reason", m.lastFailureReason)
                            put("last_used_at_ms", m.lastUsedAtMs)
                        },
                    )
                }
                put("macs", macsArr)
            }
            serversArr.put(sObj)
        }
        root.put("servers", serversArr)

        val tmp = File(dir, "pool_state.json.tmp")
        tmp.writeText(root.toString(), Charsets.UTF_8)
        if (!tmp.renameTo(stateFile)) {
            tmp.copyTo(stateFile, overwrite = true)
            tmp.delete()
        }
    }

    companion object {
        fun sortServerList(
            servers: List<StalkerServerGroup>,
            sortMode: ServerSortMode,
            nowMs: Long = System.currentTimeMillis(),
        ): List<StalkerServerGroup> {
            return when (sortMode) {
                ServerSortMode.SPEED_DESC -> servers.sortedWith(
                    compareBy<StalkerServerGroup> { speedTierRank(it) }
                        .thenByDescending { it.downloadKbps ?: -1.0 }
                        .thenBy { it.pingMs ?: Long.MAX_VALUE }
                        .thenByDescending { it.healthyMacCount(nowMs) }
                        .thenBy { it.displayHost },
                )
                ServerSortMode.PING_ASC -> servers.sortedWith(
                    compareBy<StalkerServerGroup> { pingTierRank(it) }
                        .thenBy { it.pingMs ?: Long.MAX_VALUE }
                        .thenByDescending { it.downloadKbps ?: -1.0 }
                        .thenBy { it.displayHost },
                )
                ServerSortMode.MAC_COUNT_DESC -> servers.sortedWith(
                    compareByDescending<StalkerServerGroup> { it.healthyMacCount(nowMs) }
                        .thenByDescending { it.totalMacs }
                        .thenByDescending { it.downloadKbps ?: -1.0 }
                        .thenBy { it.displayHost },
                )
                ServerSortMode.CHANNELS_DESC -> servers.sortedWith(
                    compareByDescending<StalkerServerGroup> { it.maxChannels }
                        .thenByDescending { it.downloadKbps ?: -1.0 }
                        .thenByDescending { it.healthyMacCount(nowMs) }
                        .thenBy { it.displayHost },
                )
            }
        }

        private fun speedTierRank(s: StalkerServerGroup): Int = when {
            s.downloadKbps != null -> 0
            s.speedStatus == ServerSpeedStatus.OK && s.pingMs != null -> 1
            s.speedStatus == ServerSpeedStatus.TESTING_STREAM || s.speedStatus == ServerSpeedStatus.TESTING_PING -> 2
            s.speedStatus == ServerSpeedStatus.UNTESTED -> 3
            else -> 4
        }

        private fun pingTierRank(s: StalkerServerGroup): Int = when {
            s.pingMs != null -> 0
            s.speedStatus == ServerSpeedStatus.TESTING_PING || s.speedStatus == ServerSpeedStatus.TESTING_STREAM -> 1
            s.speedStatus == ServerSpeedStatus.UNTESTED -> 2
            else -> 3
        }
    }
}
