package com.cleartune.core.network

enum class NetworkFailureCode {
    AUTHENTICATION,
    FORBIDDEN,
    NOT_FOUND,
    LOCKED,
    SERVER,
    PROTOCOL,
    TIMEOUT,
    UNAVAILABLE,
}

data class NetworkFailure(
    val code: NetworkFailureCode,
    val retryable: Boolean,
    val safeMessage: String,
) {
    override fun toString(): String = "NetworkFailure(code=$code, retryable=$retryable)"

    companion object {
        fun fromHttpStatus(status: Int): NetworkFailure = when (status) {
            401 -> NetworkFailure(NetworkFailureCode.AUTHENTICATION, false, "Authentication failed")
            403 -> NetworkFailure(NetworkFailureCode.FORBIDDEN, false, "Access denied")
            404 -> NetworkFailure(NetworkFailureCode.NOT_FOUND, false, "Remote item not found")
            423 -> NetworkFailure(NetworkFailureCode.LOCKED, false, "Remote item is locked")
            in 500..599 -> NetworkFailure(NetworkFailureCode.SERVER, true, "Remote server error")
            else -> NetworkFailure(NetworkFailureCode.PROTOCOL, false, "Unexpected server response")
        }
    }
}
