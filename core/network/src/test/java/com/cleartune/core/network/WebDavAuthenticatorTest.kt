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
    ): Response {
        val builder = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(401)
            .message("Unauthorized")
        headers.forEach { builder.addHeader("WWW-Authenticate", it) }
        return builder.build()
    }
}
