package com.cleartune.core.network

import com.cleartune.core.model.ClearTuneError
import java.net.URI

object ServerAddressNormalizer {
    fun normalize(rawAddress: String, allowInsecureHttp: Boolean): Result<String> {
        return runCatching {
            val trimmed = rawAddress.trim()
            val uri = URI(trimmed)
            val scheme = uri.scheme?.lowercase()
                ?: throw AddressException(ClearTuneError.InvalidAddress())
            if (scheme != "https" && scheme != "http") {
                throw AddressException(ClearTuneError.InvalidAddress())
            }
            if (uri.host.isNullOrBlank() || uri.userInfo != null || uri.query != null || uri.fragment != null) {
                throw AddressException(ClearTuneError.InvalidAddress())
            }
            if (scheme == "http" && !allowInsecureHttp) {
                throw AddressException(ClearTuneError.InsecureHttpBlocked())
            }
            val normalizedPath = uri.path.orEmpty().trimEnd('/')
            URI(
                scheme,
                null,
                uri.host,
                uri.port,
                if (normalizedPath.isEmpty()) "/" else "$normalizedPath/",
                null,
                null,
            ).toASCIIString()
        }
    }
}

class AddressException(val error: ClearTuneError) : IllegalArgumentException(error.userMessage)
