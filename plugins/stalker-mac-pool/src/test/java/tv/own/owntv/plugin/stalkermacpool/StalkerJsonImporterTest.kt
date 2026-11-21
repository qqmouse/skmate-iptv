package tv.own.owntv.plugin.stalkermacpool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.plugin.stalkermacpool.model.StalkerMacStatus
import tv.own.owntv.plugin.stalkermacpool.parser.StalkerJsonImporter

class StalkerJsonImporterTest {

    @Test
    fun `normalizePortalUrl strips default ports and paths while preserving custom ports`() {
        assertEquals("http://line.tvdsz.cc", StalkerJsonImporter.normalizePortalUrl("http://LINE.TVDSZ.CC:80"))
        assertEquals("http://line.tvdsz.cc", StalkerJsonImporter.normalizePortalUrl("http://line.tvdsz.cc/c/"))
        assertEquals("http://line.tvdsz.cc", StalkerJsonImporter.normalizePortalUrl("http://line.tvdsz.cc:80/portal.php"))
        assertEquals("https://secure.host.tv", StalkerJsonImporter.normalizePortalUrl("https://secure.host.tv:443/stalker_portal/server/load.php"))
        assertEquals("http://192.142.44.52:25461", StalkerJsonImporter.normalizePortalUrl("http://192.142.44.52:25461/c/"))
        assertNull(StalkerJsonImporter.normalizePortalUrl("   "))
    }

    @Test
    fun `canonicalizeMac normalizes separator variants and virtual alphanumeric macs`() {
        assertEquals("00:1A:79:AD:08:CA", StalkerJsonImporter.canonicalizeMac("00-1a-79-ad-08-ca"))
        assertEquals("A0:BB:3E:12:34:PQ", StalkerJsonImporter.canonicalizeMac("a0bb3e1234pq"))
        assertNull(StalkerJsonImporter.canonicalizeMac("00:1A:79:INVALID"))
    }

    @Test
    fun `parse groups by normalized server deduplicates and handles polymorphic fields`() {
        val nowMs = 1_795_200_000_000L // ~Nov 2026
        val sampleJson = """
            [
              {
                "portal": "http://line.tvdsz.cc:80",
                "mac": "00:1A:79:AD:08:CA",
                "proxy": "34.122.187.196:80",
                "found_on": "2026-11-18 18:43:25",
                "channels": 16506,
                "categories": { "live": 131, "movies": 93, "series": 87 },
                "max_connections": 3,
                "expire_date": "April 25, 2027, 1:41 pm",
                "stream_speed_kbps": 0.03
              },
              {
                "portal": "http://line.tvdsz.cc",
                "mac": "00-1a-79-ad-08-ca",
                "proxy": "1.2.3.4:8080",
                "found_on": "2026-11-19 08:00:00",
                "channels": 16800,
                "categories": { "live": 132, "movies": 95, "series": 88 },
                "max_connections": "Unknown",
                "expire_date": "Unlimited",
                "stream_speed_kbps": 12.5
              },
              {
                "portal": "http://line.tvdsz.cc",
                "mac": "00:1A:79:BB:22:11",
                "channels": 500,
                "max_connections": "Unknown",
                "expire_date": " Telephone 9517752228",
                "stream_speed_kbps": 0.0
              },
              {
                "portal": "http://192.142.44.52:25461/c",
                "mac": "00:1A:79:11:22:33",
                "channels": 1200,
                "max_connections": 2,
                "expire_date": "1600000000"
              },
              {
                "portal": "",
                "mac": "bad-mac"
              }
            ]
        """.trimIndent()

        val result = StalkerJsonImporter.parse(sampleJson, nowMs = nowMs)
        assertEquals(5, result.totalParsed)
        assertEquals(1, result.skippedInvalid)
        assertEquals(2, result.groups.size)
        assertEquals(3, result.uniqueRecords)

        val group1 = result.groups.first { it.portalKey == "http://line.tvdsz.cc" }
        assertEquals(2, group1.macs.size)

        val mergedMac = group1.macs.first { it.mac == "00:1A:79:AD:08:CA" }
        assertEquals(16800, mergedMac.channels)
        assertEquals(3, mergedMac.maxConnections)
        assertTrue(mergedMac.isUnlimited)
        assertFalse(mergedMac.isExpired)
        assertEquals(StalkerMacStatus.UNTESTED, mergedMac.status)

        val phoneMac = group1.macs.first { it.mac == "00:1A:79:BB:22:11" }
        assertNull(phoneMac.maxConnections)
        assertEquals("Telephone 9517752228", phoneMac.expireDateRaw)
        assertFalse(phoneMac.isExpired)

        val group2 = result.groups.first { it.portalKey == "http://192.142.44.52:25461" }
        val expiredMac = group2.macs.single()
        assertNotNull(expiredMac.expireEpochMs)
        assertTrue(expiredMac.isExpired)
        assertEquals(StalkerMacStatus.EXPIRED, expiredMac.status)
    }
}
