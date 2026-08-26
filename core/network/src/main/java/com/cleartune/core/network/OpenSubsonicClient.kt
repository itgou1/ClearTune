package com.cleartune.core.network

import com.cleartune.core.model.ClearTuneError
import com.cleartune.core.model.ConnectionResult
import com.cleartune.core.model.ServerCredentials
import com.cleartune.core.model.ServerProfile
import java.io.IOException
import java.net.SocketTimeoutException

class OpenSubsonicClient(
    private val auth: SubsonicAuth = SubsonicAuth(),
    private val apiFactory: OpenSubsonicApiFactory = OpenSubsonicApiFactory(),
) {
    suspend fun connect(credentials: ServerCredentials): ConnectionResult {
        val normalized = ServerAddressNormalizer
            .normalize(credentials.baseUrl, credentials.allowInsecureHttp)
            .getOrElse { throwable ->
                val error = (throwable as? AddressException)?.error
                    ?: ClearTuneError.InvalidAddress()
                return ConnectionResult.Failure(error)
            }
        val normalizedCredentials = credentials.copy(baseUrl = normalized)

        return try {
            val api = apiFactory.create(normalized)
            val authQuery = auth.query(normalizedCredentials)
            val ping = api.ping(authQuery)
            if (!ping.isSuccessful) {
                return ConnectionResult.Failure(
                    if (ping.code() == 401 || ping.code() == 403) {
                        ClearTuneError.Authentication()
                    } else {
                        ClearTuneError.Server(ping.code())
                    },
                )
            }
            val body = ping.body()?.response
                ?: return ConnectionResult.Failure(ClearTuneError.Server())
            if (body.status != "ok") {
                return ConnectionResult.Failure(mapSubsonicError(body.error))
            }

            val extensions = runCatching {
                api.getOpenSubsonicExtensions(auth.query(normalizedCredentials))
                    .body()
                    ?.response
                    ?.openSubsonicExtensions
                    .orEmpty()
                    .map { it.name }
                    .toSet()
            }.getOrDefault(emptySet())

            ConnectionResult.Success(
                ServerProfile(
                    baseUrl = normalized,
                    username = credentials.username,
                    serverType = body.type.ifBlank { "OpenSubsonic" },
                    serverVersion = body.serverVersion,
                    apiVersion = body.version,
                    openSubsonic = body.openSubsonic,
                    extensions = extensions,
                    allowInsecureHttp = credentials.allowInsecureHttp,
                ),
            )
        } catch (error: SocketTimeoutException) {
            ConnectionResult.Failure(ClearTuneError.Timeout(cause = error))
        } catch (error: IOException) {
            ConnectionResult.Failure(ClearTuneError.Unreachable(cause = error))
        } catch (error: Exception) {
            ConnectionResult.Failure(ClearTuneError.Unexpected(cause = error))
        }
    }

    private fun mapSubsonicError(error: SubsonicErrorDto?): ClearTuneError {
        return when (error?.code) {
            40, 41, 50 -> ClearTuneError.Authentication()
            else -> ClearTuneError.Server(code = error?.code)
        }
    }
}
