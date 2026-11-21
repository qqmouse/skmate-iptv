package tv.own.owntv.plugin.stalkermacpool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.player.ErrorInfo
import tv.own.owntv.player.PlaybackFailure
import tv.own.owntv.plugin.stalkermacpool.data.StalkerMacPoolStore
import tv.own.owntv.plugin.stalkermacpool.model.ServerSortMode
import tv.own.owntv.plugin.stalkermacpool.model.ServerSpeedStatus
import tv.own.owntv.plugin.stalkermacpool.model.StalkerMacEntry
import tv.own.owntv.plugin.stalkermacpool.model.StalkerMacStatus
import tv.own.owntv.plugin.stalkermacpool.model.StalkerServerGroup
import tv.own.owntv.plugin.stalkermacpool.playback.StalkerMacRotationPolicy

class StalkerMacRotationPolicyTest {

    @Test
    fun `classifyFailure distinguishes conflict auth and non-recoverable failures`() {
        assertEquals(
            StalkerMacRotationPolicy.FailureKind.CONFLICT_BUSY,
            StalkerMacRotationPolicy.classifyFailure(PlaybackFailure.AccountInUse, null),
        )
        assertEquals(
            StalkerMacRotationPolicy.FailureKind.CONFLICT_BUSY,
            StalkerMacRotationPolicy.classifyFailure(
                PlaybackFailure.StreamUnavailable(false),
                ErrorInfo(null, null, "Server returned HTTP 403 Forbidden"),
            ),
        )
        assertTrue(
            StalkerMacRotationPolicy.isConflictForAutoSwitch(
                PlaybackFailure.BothEnginesMpvFirst(PlaybackFailure.AccountInUse),
                null,
            ),
        )
        assertEquals(
            StalkerMacRotationPolicy.FailureKind.AUTH_INVALID,
            StalkerMacRotationPolicy.classifyFailure(
                PlaybackFailure.Raw("Authorization failed"),
                null,
            ),
        )
        assertFalse(
            StalkerMacRotationPolicy.isConflictForAutoSwitch(PlaybackFailure.NoInternet, null),
        )
    }

    @Test
    fun `rankCandidates excludes current mac and prefers healthy highest channel mac`() {
        val now = 1_000_000L
        val entries = listOf(
            StalkerMacEntry(mac = "00:1A:79:00:00:01", channels = 9000, status = StalkerMacStatus.OK),
            StalkerMacEntry(mac = "00:1A:79:00:00:02", channels = 5000, status = StalkerMacStatus.AUTH_FAILED),
            StalkerMacEntry(
                mac = "00:1A:79:00:00:03",
                channels = 12000,
                status = StalkerMacStatus.BUSY_COOLDOWN,
                cooldownUntilMs = now + 60_000L,
            ),
            StalkerMacEntry(mac = "00:1A:79:00:00:04", channels = 7000, maxConnections = 3, status = StalkerMacStatus.UNTESTED),
            StalkerMacEntry(mac = "00:1A:79:00:00:05", channels = 8500, maxConnections = 1, status = StalkerMacStatus.OK),
        )

        val ranked = StalkerMacRotationPolicy.rankCandidates(entries, currentMac = "00:1A:79:00:00:01", nowMs = now)
        assertEquals(
            listOf("00:1A:79:00:00:05", "00:1A:79:00:00:04", "00:1A:79:00:00:03"),
            ranked.map { it.mac },
        )
    }

    @Test
    fun `sortServerList orders fastest download servers first in SPEED_DESC mode`() {
        val servers = listOf(
            StalkerServerGroup(
                portalKey = "http://dead.tv",
                displayHost = "dead.tv",
                macs = listOf(StalkerMacEntry("00:1A:79:00:00:01", channels = 1000)),
                speedStatus = ServerSpeedStatus.UNREACHABLE,
            ),
            StalkerServerGroup(
                portalKey = "http://slow.tv",
                displayHost = "slow.tv",
                macs = listOf(StalkerMacEntry("00:1A:79:00:00:02", channels = 5000)),
                speedStatus = ServerSpeedStatus.OK,
                pingMs = 120L,
                downloadKbps = 640.0,
            ),
            StalkerServerGroup(
                portalKey = "http://fast.tv",
                displayHost = "fast.tv",
                macs = listOf(StalkerMacEntry("00:1A:79:00:00:03", channels = 3000)),
                speedStatus = ServerSpeedStatus.OK,
                pingMs = 45L,
                downloadKbps = 4200.0,
            ),
            StalkerServerGroup(
                portalKey = "http://untested.tv",
                displayHost = "untested.tv",
                macs = listOf(StalkerMacEntry("00:1A:79:00:00:04", channels = 2000)),
                speedStatus = ServerSpeedStatus.UNTESTED,
            ),
        )

        val bySpeed = StalkerMacPoolStore.sortServerList(servers, ServerSortMode.SPEED_DESC)
        assertEquals(listOf("fast.tv", "slow.tv", "untested.tv", "dead.tv"), bySpeed.map { it.displayHost })

        val byChannels = StalkerMacPoolStore.sortServerList(servers, ServerSortMode.CHANNELS_DESC)
        assertEquals(listOf("slow.tv", "fast.tv", "untested.tv", "dead.tv"), byChannels.map { it.displayHost })
    }
}
