package com.cleartune.core.network

import com.cleartune.core.model.ServerCredentials
import java.util.concurrent.TimeUnit
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

@OptIn(ExperimentalSerializationApi::class)
class OpenSubsonicApiFactory {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        exceptionsWithDebugInfo = false
    }

    fun create(baseUrl: String): OpenSubsonicApi {
        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(OpenSubsonicApi::class.java)
    }

    fun authorized(credentials: ServerCredentials): AuthorizedOpenSubsonicApi {
        val normalized = ServerAddressNormalizer
            .normalize(credentials.baseUrl, credentials.allowInsecureHttp)
            .getOrThrow()
        val normalizedCredentials = credentials.copy(baseUrl = normalized)
        return AuthorizedOpenSubsonicApi(
            api = create(normalized),
            credentials = normalizedCredentials,
            auth = SubsonicAuth(),
        )
    }
}

class AuthorizedOpenSubsonicApi internal constructor(
    val api: OpenSubsonicApi,
    val credentials: ServerCredentials,
    private val auth: SubsonicAuth,
) {
    fun authQuery(): Map<String, String> = auth.query(credentials)

    fun coverArtUrl(id: String, size: Int = 512): String {
        return authenticatedUrl("rest/getCoverArt.view", mapOf("id" to id, "size" to size.toString()))
    }

    fun streamUrl(
        id: String,
        maxBitRate: Int? = null,
        format: String? = null,
    ): String = authenticatedUrl(
        path = "rest/stream.view",
        parameters = buildMap {
            put("id", id)
            maxBitRate?.let { put("maxBitRate", it.toString()) }
            format?.let { put("format", it) }
        },
    )

    fun authenticatedUrl(path: String, parameters: Map<String, String>): String {
        val query = (parameters + authQuery()).entries.joinToString("&") { (key, value) ->
            "$key=${encode(value)}"
        }
        return "${credentials.baseUrl}$path?$query"
    }

    private fun encode(value: String): String = java.net.URLEncoder.encode(value, Charsets.UTF_8.name())
}
