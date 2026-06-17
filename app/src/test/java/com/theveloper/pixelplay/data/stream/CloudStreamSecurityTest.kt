package com.theveloper.pixelplay.data.stream

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CloudStreamSecurityTest {

    @Test
    fun `validateRangeHeader accepts standard ranges`() {
        val validation = CloudStreamSecurity.validateRangeHeader("bytes=0-1023")

        assertTrue(validation.isValid)
        assertTrue(validation.normalizedHeader == "bytes=0-1023")
        assertTrue(validation.startInclusive == 0L)
        assertTrue(validation.endInclusive == 1023L)
        assertFalse(validation.isSuffixRange)
    }

    @Test
    fun `validateRangeHeader accepts suffix ranges`() {
        val validation = CloudStreamSecurity.validateRangeHeader("bytes=-4096")

        assertTrue(validation.isValid)
        assertTrue(validation.isSuffixRange)
        assertTrue(validation.startInclusive == null)
        assertTrue(validation.endInclusive == 4096L)
    }

    @Test
    fun `validateRangeHeader rejects invalid formats`() {
        assertFalse(CloudStreamSecurity.validateRangeHeader("bytes=1-2,4-5").isValid)
        assertFalse(CloudStreamSecurity.validateRangeHeader("bytes=10-2").isValid)
        assertFalse(CloudStreamSecurity.validateRangeHeader("bytes=-").isValid)
    }

    @Test
    fun `isSafeRemoteStreamUrl blocks localhost`() {
        assertFalse(
            CloudStreamSecurity.isSafeRemoteStreamUrl(
                "http://127.0.0.1:8000/audio.mp3",
                allowedHostSuffixes = setOf("127.0.0.1"),
                allowHttpForAllowedHosts = true
            )
        )
    }

    @Test
    fun `isSafeRemoteStreamUrl enforces host allowlist and scheme rules`() {
        assertTrue(
            CloudStreamSecurity.isSafeRemoteStreamUrl(
                "https://m7.music.126.net/file.mp3",
                allowedHostSuffixes = setOf("music.126.net")
            )
        )
        assertTrue(
            CloudStreamSecurity.isSafeRemoteStreamUrl(
                "http://m7.music.126.net/file.mp3",
                allowedHostSuffixes = setOf("music.126.net"),
                allowHttpForAllowedHosts = true
            )
        )
        assertFalse(
            CloudStreamSecurity.isSafeRemoteStreamUrl(
                "http://evil.example.com/file.mp3",
                allowedHostSuffixes = setOf("music.126.net"),
                allowHttpForAllowedHosts = true
            )
        )
    }

    @Test
    fun `isLocalOrPrivateHost accepts local network hosts`() {
        assertTrue(CloudStreamSecurity.isLocalOrPrivateHost("localhost"))
        assertTrue(CloudStreamSecurity.isLocalOrPrivateHost("127.0.0.1"))
        assertTrue(CloudStreamSecurity.isLocalOrPrivateHost("192.168.1.20"))
        assertTrue(CloudStreamSecurity.isLocalOrPrivateHost("10.0.0.5"))
        assertTrue(CloudStreamSecurity.isLocalOrPrivateHost("172.20.0.3"))
        // Carrier-grade NAT (Tailscale and similar VPNs)
        assertTrue(CloudStreamSecurity.isLocalOrPrivateHost("100.101.102.103"))
        // Single-label LAN hostnames
        assertTrue(CloudStreamSecurity.isLocalOrPrivateHost("mynas"))
        // Local-only DNS suffixes
        assertTrue(CloudStreamSecurity.isLocalOrPrivateHost("nas.local"))
        assertTrue(CloudStreamSecurity.isLocalOrPrivateHost("nas.lan"))
        assertTrue(CloudStreamSecurity.isLocalOrPrivateHost("nas.home"))
        assertTrue(CloudStreamSecurity.isLocalOrPrivateHost("nas.internal"))
        assertTrue(CloudStreamSecurity.isLocalOrPrivateHost("nas.home.arpa"))
        // IPv6 loopback, unique-local, link-local
        assertTrue(CloudStreamSecurity.isLocalOrPrivateHost("::1"))
        assertTrue(CloudStreamSecurity.isLocalOrPrivateHost("[::1]"))
        assertTrue(CloudStreamSecurity.isLocalOrPrivateHost("fd00::1"))
        assertTrue(CloudStreamSecurity.isLocalOrPrivateHost("fe80::abcd"))
    }

    @Test
    fun `isLocalOrPrivateHost rejects public hosts`() {
        assertFalse(CloudStreamSecurity.isLocalOrPrivateHost("music.example.com"))
        assertFalse(CloudStreamSecurity.isLocalOrPrivateHost("8.8.8.8"))
        assertFalse(CloudStreamSecurity.isLocalOrPrivateHost("100.32.1.2"))
        assertFalse(CloudStreamSecurity.isLocalOrPrivateHost("2001:db8::1"))
        assertFalse(CloudStreamSecurity.isLocalOrPrivateHost("notlocal.example.org"))
        assertFalse(CloudStreamSecurity.isLocalOrPrivateHost(""))
    }

    @Test
    fun `isSupportedAudioContentType only accepts audio-safe types`() {
        assertTrue(CloudStreamSecurity.isSupportedAudioContentType("audio/mpeg"))
        assertTrue(CloudStreamSecurity.isSupportedAudioContentType("application/octet-stream"))
        assertFalse(CloudStreamSecurity.isSupportedAudioContentType("text/html"))
    }
}
