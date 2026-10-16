package ru.acestar.vpn

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.acestar.vpn.utils.FavoriteConfigLabel
import ru.acestar.vpn.update.ReleaseVersion

class FavoriteConfigLabelTest {
    @Test fun labelsUseEndpointAndTransport() {
        assertEquals("94 UDP", FavoriteConfigLabel.format("94.249.239.35", "proto udp\nremote 94.249.239.35 1194"))
        assertEquals("192 TCP", FavoriteConfigLabel.format("", "remote 192.168.1.1 443\nproto tcp-client"))
        assertEquals("10 AUTO", FavoriteConfigLabel.format("", "[Peer]\nEndpoint = 10.2.3.4:51820"))
        assertEquals("vpn AUTO", FavoriteConfigLabel.format("vpn.example.com", ""))
        assertEquals("VPN AUTO", FavoriteConfigLabel.format("", ""))
    }
    @Test fun versionCodeIsNeverGuessedFromSemver() {
        assertEquals(7, ReleaseVersion.code("1.0.6 (7)"))
        assertEquals(0, ReleaseVersion.code("1.0.6"))
        assertEquals(0, ReleaseVersion.code("1.0.6 (9999999999999)"))
    }
}
