package com.music.musica.core.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.security.MessageDigest

class SapisidHashSignerTest {

    @Test
    fun extractSapisid_prefersStandardAndSecureCookieNames() {
        val cookie1 = "PREF=tz=Asia.Makassar; SAPISID=abc123/XYZ; SID=999"
        assertEquals("abc123/XYZ", SapisidHashSigner.extractSapisid(cookie1))

        val cookie2 = "HSID=111; __Secure-3PAPISID=secureToken_42; VISITOR_INFO1_LIVE=foo"
        assertEquals("secureToken_42", SapisidHashSigner.extractSapisid(cookie2))

        assertNull(SapisidHashSigner.extractSapisid("PREF=hl=en; YSC=nope"))
        assertNull(SapisidHashSigner.extractSapisid(null))
    }

    @Test
    fun sign_computesExpectedSha1SapisidHash() {
        val ts = 1700000000L
        val sapisid = "test_sapisid_value"
        val origin = "https://music.youtube.com"
        val expectedHash = MessageDigest.getInstance("SHA-1")
            .digest("$ts $sapisid $origin".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

        val actual = SapisidHashSigner.sign(sapisid = sapisid, origin = origin, epochSeconds = ts)
        assertEquals("SAPISIDHASH ${ts}_$expectedHash", actual)
    }

    @Test
    fun authorizationHeader_returnsNullWhenCookieLacksSapisid() {
        assertNull(SapisidHashSigner.authorizationHeader("PREF=hl=id"))
    }
}
