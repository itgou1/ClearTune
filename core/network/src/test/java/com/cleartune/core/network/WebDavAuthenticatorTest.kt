package com.cleartune.core.network

import com.cleartune.core.contracts.WebDavCredential
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebDavAuthenticatorTest {
    private val base = WebDavUrlPolicy.normalizeBaseUrl("https://music.example/dav", false)

    @Test
    fun prefers_digest_over_basic_and_never_preemptively_authenticates() {
        val authenticator = WebDavAuthenticator(
            baseUrl = base,
            credentialProvider = { WebDavCredential("Mufasa", "Circle Of Life".toCharArray()) },
            nonceSource = { "0a4f113b" },
        )
        val request = Request.Builder().url(base.resolve("library/")!!).build()
        val response = response(
            request = request,
            headers = listOf(
                "Basic realm=\"fallback\"",
                "Digest realm=\"testrealm@host.com\", qop=\"auth\", nonce=\"dcd98b7102dd2f0e8b11d0f600bfb0c093\"",
            ),
        )

        val authenticated = authenticator.authenticate(null, response)!!

        assertTrue(authenticated.header("Authorization")!!.startsWith("Digest "))
        assertNull(request.header("Authorization"))
    }

    @Test
    fun skips_unsupported_digest_and_uses_a_later_supported_challenge() {
        val authenticator = WebDavAuthenticator(
            baseUrl = base,
            credentialProvider = { WebDavCredential("user", "secret".toCharArray()) },
            nonceSource = { "cnonce" },
        )
        val request = Request.Builder().url(base.resolve("library/song.flac")!!).build()

        val authenticated = authenticator.authenticate(
            null,
            response(
                request,
                listOf(
                    "Digest realm=\"music\", nonce=\"unsupported\", algorithm=SHA-512, qop=\"auth\"",
                    "Digest realm=\"music\", nonce=\"supported\", algorithm=SHA-256, qop=\"auth\"",
                    "Basic realm=\"fallback\"",
                ),
            ),
        )!!

        val authorization = authenticated.header("Authorization")!!
        assertTrue(authorization, authorization.startsWith("Digest "))
        assertTrue(authorization, authorization.contains("nonce=\"supported\""))
        assertTrue(authorization, authorization.contains("algorithm=SHA-256"))
    }

    @Test
    fun permits_one_stale_nonce_refresh_within_the_two_challenge_ceiling() {
        val authenticator = WebDavAuthenticator(
            baseUrl = base,
            credentialProvider = { WebDavCredential("user", "secret".toCharArray()) },
            nonceSource = { "cnonce" },
        )
        val initial = Request.Builder().url(base.resolve("library/song.flac")!!).build()
        val firstChallenge = response(
            initial,
            listOf("Digest realm=\"music\", nonce=\"nonce-1\", algorithm=SHA-256, qop=\"auth\""),
        )
        val firstAuthenticated = authenticator.authenticate(null, firstChallenge)!!
        val staleChallenge = response(
            firstAuthenticated,
            listOf("Digest realm=\"music\", nonce=\"nonce-2\", algorithm=SHA-256, qop=\"auth\", stale=true"),
            priorResponse = firstChallenge,
        )

        val refreshed = authenticator.authenticate(null, staleChallenge)!!

        val refreshedHeader = refreshed.header("Authorization")!!
        assertTrue(refreshedHeader, refreshedHeader.contains("nonce=\"nonce-2\""))
        assertTrue(refreshedHeader, refreshedHeader.contains("nc=00000001"))
        val thirdChallenge = response(
            refreshed,
            listOf("Digest realm=\"music\", nonce=\"nonce-3\", algorithm=SHA-256, qop=\"auth\", stale=true"),
            priorResponse = staleChallenge,
        )
        assertNull(authenticator.authenticate(null, thirdChallenge))
    }

    @Test
    fun nonce_count_increments_for_one_nonce_and_resets_for_a_new_nonce() {
        val authenticator = WebDavAuthenticator(
            baseUrl = base,
            credentialProvider = { WebDavCredential("user", "secret".toCharArray()) },
            nonceSource = { "cnonce" },
        )
        val request = Request.Builder().url(base.resolve("library/song.flac")!!).build()

        fun authorization(nonce: String): String = authenticator.authenticate(
            null,
            response(
                request,
                listOf("Digest realm=\"music\", nonce=\"$nonce\", algorithm=SHA-256, qop=\"auth\""),
            ),
        )!!.header("Authorization")!!

        assertTrue(authorization("nonce-1").contains("nc=00000001"))
        assertTrue(authorization("nonce-1").contains("nc=00000002"))
        assertTrue(authorization("nonce-2").contains("nc=00000001"))
    }

    @Test
    fun refuses_cross_origin_and_outside_base_subtree() {
        val authenticator = WebDavAuthenticator(
            baseUrl = base,
            credentialProvider = { WebDavCredential("user", charArrayOf('p')) },
        )
        val foreign = Request.Builder().url("https://evil.example/dav/").build()
        val sibling = Request.Builder().url("https://music.example/other/").build()

        assertNull(authenticator.authenticate(null, response(foreign, listOf("Basic realm=\"x\""))))
        assertNull(authenticator.authenticate(null, response(sibling, listOf("Basic realm=\"x\""))))
    }

    @Test
    fun stops_after_an_authenticated_failure() {
        val authenticator = WebDavAuthenticator(
            baseUrl = base,
            credentialProvider = { WebDavCredential("user", charArrayOf('p')) },
        )
        val initial = Request.Builder().url(base).build()
        val first = authenticator.authenticate(null, response(initial, listOf("Basic realm=\"x\"")))!!

        assertNull(authenticator.authenticate(null, response(first, listOf("Basic realm=\"x\""))))
    }

    private fun response(
        request: Request,
        headers: List<String>,
        priorResponse: Response? = null,
    ): Response {
        val builder = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(401)
            .message("Unauthorized")
            .priorResponse(priorResponse)
        headers.forEach { builder.addHeader("WWW-Authenticate", it) }
        return builder.build()
    }
}
