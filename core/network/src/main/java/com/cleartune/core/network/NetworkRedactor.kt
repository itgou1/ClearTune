package com.cleartune.core.network

object NetworkRedactor {
    private val sensitiveQuery = Regex("(?i)([?&](?:p|t|s|u)=)[^&]*")

    fun redact(value: String): String {
        return value.replace(sensitiveQuery) { match -> "${match.groupValues[1]}██" }
    }
}
