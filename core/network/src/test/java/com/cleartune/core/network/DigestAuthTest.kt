package com.cleartune.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DigestAuthTest {
    @Test
    fun matches_rfc_md5_qop_auth_vector() {
        val authorization = DigestAuth.authorization(
            challenge = "Digest realm=\"testrealm@host.com\", qop=\"auth,auth-int\", nonce=\"dcd98b7102dd2f0e8b11d0f600bfb0c093\", opaque=\"5ccc069c403ebaf9f0171e9517f40e41\"",
            method = "GET",
            requestUri = "/dir/index.html",
            username = "Mufasa",
            password = "Circle Of Life".toCharArray(),
            nonceCount = 1,
            cnonce = "0a4f113b",
        )

        assertTrue(authorization.startsWith("Digest "))
        assertTrue(authorization.contains("response=\"6629fae49393a05397450978507c4ef1\""))
        assertTrue(authorization.contains("nc=00000001"))
        assertTrue(authorization.contains("qop=auth"))
    }

    @Test
    fun parses_escaped_quotes_and_commas_without_splitting_inside_quotes() {
        val challenge = DigestAuth.parseChallenge(
            "Digest realm=\"music, \\\"private\\\"\", nonce=\"abc\", algorithm=SHA-256, qop=\"auth\"",
        )

        assertEquals("music, \"private\"", challenge.realm)
        assertEquals("abc", challenge.nonce)
        assertEquals(DigestAlgorithm.SHA_256, challenge.algorithm)
    }

    @Test
    fun rejects_auth_int_only_and_unknown_algorithms() {
        assertThrows(UnsupportedDigestChallenge::class.java) {
            DigestAuth.authorization(
                challenge = "Digest realm=\"r\", nonce=\"n\", qop=\"auth-int\"",
                method = "GET",
                requestUri = "/",
                username = "u",
                password = charArrayOf('p'),
                nonceCount = 1,
                cnonce = "c",
            )
        }
        assertThrows(UnsupportedDigestChallenge::class.java) {
            DigestAuth.parseChallenge("Digest realm=\"r\", nonce=\"n\", algorithm=SHA-512")
        }
    }
}
