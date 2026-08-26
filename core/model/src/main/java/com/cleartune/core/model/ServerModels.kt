package com.cleartune.core.model

data class ServerCredentials(
    val baseUrl: String,
    val username: String,
    val password: String,
    val allowInsecureHttp: Boolean = false,
)

data class ServerProfile(
    val baseUrl: String,
    val username: String,
    val serverType: String,
    val serverVersion: String,
    val apiVersion: String,
    val openSubsonic: Boolean,
    val extensions: Set<String> = emptySet(),
    val allowInsecureHttp: Boolean = false,
)

sealed interface ConnectionResult {
    data class Success(val profile: ServerProfile) : ConnectionResult
    data class Failure(val error: ClearTuneError) : ConnectionResult
}

sealed class ClearTuneError(
    open val userMessage: String,
    open val cause: Throwable? = null,
) {
    data class InvalidAddress(
        override val userMessage: String = "服务器地址无效",
    ) : ClearTuneError(userMessage)

    data class InsecureHttpBlocked(
        override val userMessage: String = "当前地址使用不安全的 HTTP 连接",
    ) : ClearTuneError(userMessage)

    data class Authentication(
        override val userMessage: String = "用户名或密码不正确",
    ) : ClearTuneError(userMessage)

    data class Timeout(
        override val userMessage: String = "连接超时，请稍后重试",
        override val cause: Throwable? = null,
    ) : ClearTuneError(userMessage, cause)

    data class Unreachable(
        override val userMessage: String = "无法连接音乐服务器",
        override val cause: Throwable? = null,
    ) : ClearTuneError(userMessage, cause)

    data class Server(
        val code: Int? = null,
        override val userMessage: String = "音乐服务器返回了错误",
    ) : ClearTuneError(userMessage)

    data class Unexpected(
        override val userMessage: String = "出现未知错误，请稍后重试",
        override val cause: Throwable? = null,
    ) : ClearTuneError(userMessage, cause)
}
