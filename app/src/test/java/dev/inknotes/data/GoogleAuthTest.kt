package dev.inknotes.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class GoogleAuthTest {
    @Test
    fun pkceChallengeIsTheRfcExample() {
        // RFC 7636, appendix B.
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", GoogleAuth.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
        val v = GoogleAuth.verifier()
        assertTrue(v.length in 43..128 && v.all { it.isLetterOrDigit() || it == '-' || it == '_' })
    }

    @Test
    fun authUrlHasWhatGoogleNeeds() {
        val url = GoogleAuth.authUrl("id.apps.googleusercontent.com", "http://127.0.0.1:5555", "ch", "st")
        assertTrue(url.startsWith("https://accounts.google.com/o/oauth2/v2/auth?client_id=id.apps.googleusercontent.com"))
        assertTrue(url.contains("redirect_uri=http%3A%2F%2F127.0.0.1%3A5555"))
        assertTrue(url.contains("scope=https%3A%2F%2Fwww.googleapis.com%2Fauth%2Fdrive.file%20openid%20email"))
        assertTrue(url.contains("code_challenge_method=S256") && url.contains("access_type=offline"))
    }

    @Test
    fun loopbackRepliesAreRead() {
        assertEquals(GoogleAuth.Reply.Code("4/0Ab_c"), GoogleAuth.parse("GET /?state=st&code=4%2F0Ab_c&scope=x HTTP/1.1", "st"))
        assertEquals(GoogleAuth.Reply.Error("access_denied"), GoogleAuth.parse("GET /?error=access_denied&state=st HTTP/1.1", "st"))
        assertEquals(GoogleAuth.Reply.Other, GoogleAuth.parse("GET /favicon.ico HTTP/1.1", "st"))
        // Someone else's answer (another state) is not taken.
        assertEquals(GoogleAuth.Reply.Other, GoogleAuth.parse("GET /?state=evil&code=x HTTP/1.1", "st"))
    }

    @Test
    fun emailFromIdToken() {
        val payload = Base64.getUrlEncoder().withoutPadding().encodeToString("{\"iss\":\"x\",\"email\":\"a.b@gmail.com\"}".toByteArray())
        assertEquals("a.b@gmail.com", GoogleAuth.emailOf("h.$payload.s"))
        assertEquals(null, GoogleAuth.emailOf("garbage"))
    }
}
