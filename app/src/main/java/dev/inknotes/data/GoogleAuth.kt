package dev.inknotes.data

import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Signing in to Google without Google's services on the device (Huawei): the system browser
 * shows Google's page, Google sends the answer to a tiny server of the app on 127.0.0.1
 * ("loopback", the way for installed apps with a "Desktop" OAuth client). PKCE keeps the code
 * useless to anyone else. This part is plain JVM; the server and requests are in [DriveClient].
 */
object GoogleAuth {
    const val AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth"
    const val TOKEN_URL = "https://oauth2.googleapis.com/token"

    /** Files the app made itself, and the address of the account (to show who is signed in). */
    const val SCOPE = "https://www.googleapis.com/auth/drive.file openid email"

    private val random = SecureRandom()

    private fun b64(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    /** A random PKCE verifier (43…128 characters of base64url). */
    fun verifier(): String = b64(ByteArray(48).also(random::nextBytes))

    fun challenge(verifier: String): String = b64(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    fun state(): String = b64(ByteArray(16).also(random::nextBytes))

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    fun form(params: Map<String, String>) = params.entries.joinToString("&") { (k, v) -> enc(k) + "=" + enc(v) }

    fun authUrl(clientId: String, redirect: String, challenge: String, state: String): String =
        AUTH_URL + "?" + form(
            linkedMapOf(
                "client_id" to clientId,
                "redirect_uri" to redirect,
                "response_type" to "code",
                "scope" to SCOPE,
                "code_challenge" to challenge,
                "code_challenge_method" to "S256",
                // A refresh token, so that one sign-in is enough.
                "access_type" to "offline",
                "prompt" to "consent",
                "state" to state,
            ),
        )

    /** What came back to the loopback address. */
    sealed class Reply {
        data class Code(val code: String) : Reply()
        data class Error(val error: String) : Reply()

        /** Not for us (the browser asking for an icon, a wrong state). */
        data object Other : Reply()
    }

    /** The first line of the browser's request ("GET /?code=…&state=… HTTP/1.1"). */
    fun parse(requestLine: String, expectedState: String): Reply {
        val target = requestLine.split(" ").getOrNull(1) ?: return Reply.Other
        val query = target.substringAfter('?', "")
        if (query.isEmpty()) return Reply.Other
        val params = query.split("&").mapNotNull { p ->
            val k = p.substringBefore('=')
            val v = p.substringAfter('=', "")
            if (k.isEmpty()) null else URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
        }.toMap()
        if (params["state"] != expectedState) return Reply.Other
        params["error"]?.let { return Reply.Error(it) }
        return params["code"]?.takeIf { it.isNotBlank() }?.let { Reply.Code(it) } ?: Reply.Other
    }

    /** The account's address from an id_token (its middle part is base64url JSON). */
    fun emailOf(idToken: String?): String? {
        val payload = idToken?.split(".")?.getOrNull(1) ?: return null
        val json = runCatching { String(Base64.getUrlDecoder().decode(payload), Charsets.UTF_8) }.getOrNull() ?: return null
        return Regex("\"email\"\\s*:\\s*\"([^\"]+)\"").find(json)?.groupValues?.get(1)
    }
}
