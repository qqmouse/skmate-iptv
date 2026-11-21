package tv.own.owntv.plugin.stalkermacpool.parser

import org.json.JSONArray
import org.json.JSONObject
import tv.own.owntv.plugin.stalkermacpool.model.StalkerMacEntry
import tv.own.owntv.plugin.stalkermacpool.model.StalkerMacStatus
import tv.own.owntv.plugin.stalkermacpool.model.StalkerServerGroup
import java.net.URI
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Lenient parser for Stalker scanner JSON exports.
 *
 * Normalizes portal URLs (default `:80`/`:443`, `/c`, API `.php` suffixes), canonicalizes MACs,
 * deduplicates `(portalKey, mac)`, and ignores public scanner proxies for playback.
 */
object StalkerJsonImporter {

    data class ExpireParseResult(
        val rawDisplay: String?,
        val epochMs: Long?,
        val isUnlimited: Boolean,
        val isExpired: Boolean,
    )

    data class ParsedImport(
        val groups: List<StalkerServerGroup>,
        val totalParsed: Int,
        val uniqueRecords: Int,
        val skippedInvalid: Int,
    )

    private val apiSuffixes = listOf(
        "/stalker_portal/server/load.php",
        "/server/load.php",
        "/portal.php",
    )

    private val macStripRegex = Regex("[:\\-. ]")
    private val whitespaceRegex = Regex("\\s+")

    private val dateFormats = listOf(
        "MMMM d, yyyy, h:mm a",
        "MMMM d, yyyy, hh:mm a",
        "MMMM d, yyyy",
        "MMM d, yyyy, h:mm a",
        "MMM d, yyyy",
        "yyyy-MM-dd HH:mm:ss",
        "yyyy-MM-dd",
    )

    fun canonicalizeMac(raw: String?): String? {
        val cleaned = raw?.trim()?.replace(macStripRegex, "") ?: return null
        if (cleaned.length != 12) return null
        if (!cleaned.all { it.isDigit() || it.lowercaseChar() in 'a'..'z' }) return null
        return cleaned.uppercase(Locale.US).chunked(2).joinToString(":")
    }

    fun normalizePortalUrl(raw: String?): String? {
        val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val withScheme = if (trimmed.contains("://")) trimmed else "http://$trimmed"
        val uri = runCatching { URI(withScheme) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase(Locale.US)?.takeIf { it == "http" || it == "https" } ?: return null

        val authority = uri.authority?.takeIf { it.isNotBlank() } ?: return null
        val rawHost = uri.host?.lowercase(Locale.US) ?: authority.substringBefore(':').lowercase(Locale.US)
        if (rawHost.isBlank()) return null

        val explicitPort = uri.port.takeIf { it > 0 } ?: authority
            .takeIf { !it.startsWith("[") && it.contains(':') }
            ?.substringAfterLast(':')
            ?.toIntOrNull()

        val includePort = explicitPort != null &&
            explicitPort > 0 &&
            !(scheme == "http" && explicitPort == 80) &&
            !(scheme == "https" && explicitPort == 443)

        var path = (uri.rawPath ?: "").trimEnd('/')
        var changed = true
        while (changed) {
            changed = false
            val lower = path.lowercase(Locale.US)
            for (suffix in apiSuffixes) {
                if (lower.endsWith(suffix)) {
                    path = path.dropLast(suffix.length).trimEnd('/')
                    changed = true
                    break
                }
            }
            if (!changed && lower.endsWith("/c")) {
                path = path.dropLast(2).trimEnd('/')
                changed = true
            }
        }

        val portPart = if (includePort) ":$explicitPort" else ""
        return "$scheme://$rawHost$portPart$path"
    }

    fun displayHostOf(portalKey: String): String =
        portalKey
            .removePrefix("http://")
            .removePrefix("https://")
            .ifBlank { portalKey }

    fun parseMaxConnections(value: Any?): Int? {
        if (value == null || value === JSONObject.NULL) return null
        return when (value) {
            is Number -> value.toInt().takeIf { it > 0 }
            is String -> value.trim().toIntOrNull()?.takeIf { it > 0 }
            else -> null
        }
    }

    fun parseExpireDate(raw: String?, nowMs: Long = System.currentTimeMillis()): ExpireParseResult {
        val normalized = raw?.replace(whitespaceRegex, " ")?.trim()?.takeIf {
            it.isNotEmpty() && !it.equals("null", ignoreCase = true)
        } ?: return ExpireParseResult(rawDisplay = null, epochMs = null, isUnlimited = false, isExpired = false)

        if (normalized.equals("Unlimited", ignoreCase = true)) {
            return ExpireParseResult(
                rawDisplay = normalized,
                epochMs = null,
                isUnlimited = true,
                isExpired = false,
            )
        }
        if (normalized.equals("Unknown", ignoreCase = true)) {
            return ExpireParseResult(
                rawDisplay = normalized,
                epochMs = null,
                isUnlimited = false,
                isExpired = false,
            )
        }

        // Handle plain numeric epochs (10-digit seconds or 13-digit millis).
        if (normalized.all { it.isDigit() }) {
            val num = normalized.toLongOrNull()
            if (num != null && normalized.length in 10..13) {
                val epochMs = if (normalized.length <= 10) num * 1000L else num
                return ExpireParseResult(
                    rawDisplay = normalized,
                    epochMs = epochMs,
                    isUnlimited = false,
                    isExpired = epochMs < nowMs,
                )
            }
            return ExpireParseResult(
                rawDisplay = normalized,
                epochMs = null,
                isUnlimited = false,
                isExpired = false,
            )
        }

        for (pattern in dateFormats) {
            val sdf = SimpleDateFormat(pattern, Locale.US).apply {
                isLenient = true
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val pos = ParsePosition(0)
            val parsed = sdf.parse(normalized, pos)
            if (parsed != null && pos.index == normalized.length) {
                val epochMs = parsed.time
                return ExpireParseResult(
                    rawDisplay = normalized,
                    epochMs = epochMs,
                    isUnlimited = false,
                    isExpired = epochMs < nowMs,
                )
            }
        }

        return ExpireParseResult(
            rawDisplay = normalized,
            epochMs = null,
            isUnlimited = false,
            isExpired = false,
        )
    }

    private data class ServerAccumulator(
        val portalKey: String,
        val rawPortals: LinkedHashSet<String> = linkedSetOf(),
        val macMap: LinkedHashMap<String, StalkerMacEntry> = linkedMapOf(),
    )

    fun parse(jsonText: String, nowMs: Long = System.currentTimeMillis()): ParsedImport {
        val trimmed = jsonText.trim().removePrefix("\uFEFF")
        val array = JSONArray(trimmed)
        var totalParsed = 0
        var skippedInvalid = 0

        val byServer = linkedMapOf<String, ServerAccumulator>()

        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i)
            if (obj == null) {
                skippedInvalid++
                continue
            }
            totalParsed++

            val rawPortal = obj.optString("portal", "").trim()
            val portalKey = normalizePortalUrl(rawPortal)
            val mac = canonicalizeMac(obj.optString("mac", ""))
            if (portalKey == null || mac == null) {
                skippedInvalid++
                continue
            }

            val categoriesObj = obj.optJSONObject("categories")
            val channels = obj.optInt("channels", 0).coerceAtLeast(0)
            val liveCat = categoriesObj?.optInt("live", 0)?.coerceAtLeast(0) ?: 0
            val movieCat = categoriesObj?.optInt("movies", 0)?.coerceAtLeast(0) ?: 0
            val seriesCat = categoriesObj?.optInt("series", 0)?.coerceAtLeast(0) ?: 0
            val maxConnections = parseMaxConnections(obj.opt("max_connections"))
            val expireRawOpt = if (obj.isNull("expire_date")) null else obj.opt("expire_date")?.toString()
            val exp = parseExpireDate(expireRawOpt, nowMs)
            val foundOn = obj.optString("found_on", "").trim().takeIf { it.isNotEmpty() && it != "null" }
            val proxyMeta = obj.optString("proxy", "").trim().takeIf { it.isNotEmpty() && it != "null" }
            val scannerSpeed = obj.optDouble("stream_speed_kbps", 0.0).takeIf { it.isFinite() && it >= 0.0 } ?: 0.0

            val candidate = StalkerMacEntry(
                mac = mac,
                channels = channels,
                liveCategories = liveCat,
                movieCategories = movieCat,
                seriesCategories = seriesCat,
                maxConnections = maxConnections,
                expireDateRaw = exp.rawDisplay,
                expireEpochMs = exp.epochMs,
                isUnlimited = exp.isUnlimited,
                isExpired = exp.isExpired,
                foundOn = foundOn,
                proxyMeta = proxyMeta,
                scannerSpeedKbps = scannerSpeed,
                status = if (exp.isExpired) StalkerMacStatus.EXPIRED else StalkerMacStatus.UNTESTED,
            )

            val acc = byServer.getOrPut(portalKey) { ServerAccumulator(portalKey) }
            if (rawPortal.isNotEmpty()) acc.rawPortals.add(rawPortal)
            val existing = acc.macMap[mac]
            acc.macMap[mac] = if (existing == null) candidate else mergeMacEntry(existing, candidate)
        }

        val groups = byServer.values.map { acc ->
            StalkerServerGroup(
                portalKey = acc.portalKey,
                rawPortalSamples = acc.rawPortals.toList(),
                displayHost = displayHostOf(acc.portalKey),
                macs = acc.macsSorted(),
            )
        }
        val uniqueRecords = groups.sumOf { it.macs.size }
        return ParsedImport(
            groups = groups,
            totalParsed = totalParsed,
            uniqueRecords = uniqueRecords,
            skippedInvalid = skippedInvalid,
        )
    }

    private fun ServerAccumulator.macsSorted(): List<StalkerMacEntry> =
        macMap.values.sortedWith(
            compareByDescending<StalkerMacEntry> { it.channels }
                .thenByDescending { it.maxConnections ?: 0 }
                .thenBy { it.mac },
        )

    fun mergeMacEntry(existing: StalkerMacEntry, incoming: StalkerMacEntry, preserveRuntimeState: Boolean = false): StalkerMacEntry {
        val preferIncoming =
            (incoming.foundOn ?: "") > (existing.foundOn ?: "") ||
                incoming.channels > existing.channels

        val bestChannels = maxOf(existing.channels, incoming.channels)
        val bestLive = maxOf(existing.liveCategories, incoming.liveCategories)
        val bestMovie = maxOf(existing.movieCategories, incoming.movieCategories)
        val bestSeries = maxOf(existing.seriesCategories, incoming.seriesCategories)
        val bestMaxConn = if (preferIncoming) {
            incoming.maxConnections ?: existing.maxConnections
        } else {
            existing.maxConnections ?: incoming.maxConnections
        }

        val (bestExpRaw, bestExpEpoch, bestUnlimited, bestExpired) = when {
            incoming.isUnlimited || incoming.expireEpochMs != null ->
                Quad(incoming.expireDateRaw, incoming.expireEpochMs, incoming.isUnlimited, incoming.isExpired)
            existing.isUnlimited || existing.expireEpochMs != null ->
                Quad(existing.expireDateRaw, existing.expireEpochMs, existing.isUnlimited, existing.isExpired)
            else -> {
                val chosen = if (preferIncoming) incoming else existing
                Quad(
                    chosen.expireDateRaw ?: existing.expireDateRaw ?: incoming.expireDateRaw,
                    chosen.expireEpochMs,
                    chosen.isUnlimited,
                    chosen.isExpired,
                )
            }
        }

        val mergedStatus = when {
            preserveRuntimeState && existing.status != StalkerMacStatus.UNTESTED -> existing.status
            bestExpired -> StalkerMacStatus.EXPIRED
            else -> StalkerMacStatus.UNTESTED
        }

        return StalkerMacEntry(
            mac = existing.mac,
            channels = bestChannels,
            liveCategories = bestLive,
            movieCategories = bestMovie,
            seriesCategories = bestSeries,
            maxConnections = bestMaxConn,
            expireDateRaw = bestExpRaw,
            expireEpochMs = bestExpEpoch,
            isUnlimited = bestUnlimited,
            isExpired = bestExpired,
            foundOn = listOfNotNull(existing.foundOn, incoming.foundOn).maxOrNull(),
            proxyMeta = if (preferIncoming) incoming.proxyMeta ?: existing.proxyMeta else existing.proxyMeta ?: incoming.proxyMeta,
            scannerSpeedKbps = maxOf(existing.scannerSpeedKbps, incoming.scannerSpeedKbps),
            status = mergedStatus,
            cooldownUntilMs = if (preserveRuntimeState) existing.cooldownUntilMs else 0L,
            lastFailureReason = if (preserveRuntimeState) existing.lastFailureReason else null,
            lastUsedAtMs = if (preserveRuntimeState) existing.lastUsedAtMs else 0L,
        )
    }

    private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
}
