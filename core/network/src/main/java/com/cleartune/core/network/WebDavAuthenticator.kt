package com.cleartune.core.network

import com.cleartune.core.contracts.WebDavCredential
import java.nio.charset.StandardCharsets
import java.util.UUID
import okhttp3.Authenticator
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route

fun interface CredentialProvider {
    fun get(): WebDavCredential?
}

fun interface NonceSource {
    fun nextCnonce(): String
}

class WebDavAuthenticator(
    private val baseUrl: HttpUrl,
    private val credentialProvider: CredentialProvider,
    private val nonceSource: NonceSource = NonceSource { UUID.randomUUID().toString().replace("-", "") },
) : Authenticator {
    private val nonceLock = Any()
    private var currentNonce: String? = null
    private var nonceCount = 0

    override fun authenticate(route: Route?, response: Response): Request? {
        val request = response.request
        if (!WebDavUrlPolicy.isSameOrigin(baseUrl, request.url) ||
            !WebDavUrlPolicy.isInBaseSubtree(baseUrl, request.url)
        ) {
            return null
        }
        val challengeCount = generateSequence(response) { it.priorResponse }.count { it.code == 401 }
        if (challengeCount > MAX_CHALLENGES) return null
        val credential = credentialProvider.get() ?: return null
        val challenges = response.headers.values("WWW-Authenticate")
        val digest = challenges.asSequence()
            .filter { it.trimStart().startsWith("Digest ", ignoreCase = true) }
            .mapNotNull { challenge ->
                try {
                    challenge to DigestAuth.parseChallenge(challenge)
                } catch (_: UnsupportedDigestChallenge) {
                    null
                }
            }
            .firstOrNull()
        val basicOffered = challenges.any { it.trimStart().startsWith("Basic ", ignoreCase = true) }
        val alreadyAuthenticated = request.header("Authorization") != null
        if (alreadyAuthenticated && (digest == null || !digest.second.stale || challengeCount != MAX_CHALLENGES)) {
            return null
        }

        val authorization = when {
            digest != null -> digestHeader(request, digest.first, digest.second, credential)
            basicOffered && !alreadyAuthenticated -> Credentials.basic(
                credential.username,
                String(credential.password),
                StandardCharsets.UTF_8,
            )
            else -> return null
        }
        return request.newBuilder().header("Authorization", authorization).build()
    }

    private fun digestHeader(
        request: Request,
        challenge: String,
        parsedChallenge: DigestChallenge,
        credential: WebDavCredential,
    ): String {
        val requestUri = buildString {
            append(request.url.encodedPath)
            request.url.encodedQuery?.let { append('?').append(it) }
        }
        return DigestAuth.authorization(
            challenge = challenge,
            method = request.method,
            requestUri = requestUri,
            username = credential.username,
            password = credential.password,
            nonceCount = nextNonceCount(parsedChallenge.nonce),
            cnonce = nonceSource.nextCnonce(),
        )
    }

    private fun nextNonceCount(nonce: String): Int = synchronized(nonceLock) {
        if (currentNonce != nonce) {
            currentNonce = nonce
            nonceCount = 0
        }
        ++nonceCount
    }

    override fun toString(): String = "WebDavAuthenticator(baseOrigin=${baseUrl.scheme}://${baseUrl.host}:${baseUrl.port})"

    private companion object {
        const val MAX_CHALLENGES = 2
    }
}
