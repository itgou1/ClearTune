package com.cleartune.core.datastore

import com.cleartune.core.model.*
import java.util.Base64

/** Encoding is only framing; the store encrypts this entire payload with CredentialCipher. */
internal object MusicTagSettingsCodec {
    fun encode(value: MusicTagSettings): String = "v2\n" + with(value) {
        listOf(baseUrl, username, password, sourceRoot, targetRoot, source, allowHttp.toString(),
            connectionMode.name, ssh.host, ssh.port.toString(), ssh.username, ssh.auth.name,
            ssh.password, ssh.privateKey, ssh.passphrase, ssh.serviceUrl, ssh.hostKey, ssh.hostKeyEndpoint)
    }.joinToString("\n") { Base64.getEncoder().encodeToString(it.toByteArray(Charsets.UTF_8)) }

    fun decode(payload: String): MusicTagSettings {
        val versioned = payload.startsWith("v2\n")
        val values = (if (versioned) payload.removePrefix("v2\n") else payload).split('\n').map {
            String(Base64.getDecoder().decode(it), Charsets.UTF_8)
        }
        require(values.size == if (versioned) 18 else 7) { "标签服务配置格式不兼容" }
        val legacy = MusicTagSettings(values[0], values[1], values[2], values[3], values[4], values[5], values[6].toBooleanStrict())
        return if (!versioned) legacy else legacy.copy(connectionMode = MusicTagConnectionMode.valueOf(values[7]),
            ssh = MusicTagSshSettings(values[8], values[9].toInt(), values[10], MusicTagSshAuth.valueOf(values[11]),
                values[12], values[13], values[14], values[15], values[16], values[17]))
    }
}
