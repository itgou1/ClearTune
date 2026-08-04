package com.cleartune.core.network

import com.cleartune.core.contracts.WebDavCredential
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
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
    private val nonceCount = AtomicInteger(0)

    override fun authenticate(route: Route?, response: Response): Request? {
        val request = response.request
        if (!WebDavUrlPolicy.isSameOrigin(baseUrl, request.url) ||
            !WebDavUrlPolicy.isInBaseSubtree(baseUrl, request.url)
        ) {
            return null
        }
        if (request.header("Authorization") != null) return null
        val credential = credentialProvider.get() ?: return null
        val challenges = response.headers.values("WWW-Authenticate")
        val digest = challenges.firstOrNull { it.trimStart().startsWith("Digest ", ignoreCase = true) }
        val basicOffered = challenges.any { it.trimStart().startsWith("Basic ", ignoreCase = true) }

        val authorization = when {
            digest != null -> digestHeader(request, digest, credential)
            basicOffered -> Credentials.basic(
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
            nonceCount = nonceCount.incrementAndGet(),
            cnonce = nonceSource.nextCnonce(),
        )
    }

    override fun toString(): String = "WebDavAuthenticator(baseOrigin=${baseUrl.scheme}://${baseUrl.host}:${baseUrl.port})"
}
