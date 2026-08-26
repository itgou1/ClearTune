package com.cleartune.app.settings

import com.cleartune.app.BuildConfig
import com.cleartune.core.model.VersionComparator
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class UpdateRelease(
    val version: String,
    val name: String,
    val pageUrl: String,
    val notes: String,
    val newer: Boolean,
)

@Singleton
class UpdateChecker @Inject constructor() {
    suspend fun check(): Result<UpdateRelease> = withContext(Dispatchers.IO) {
        runCatching {
            val connection = (URL(LATEST_RELEASE_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 10_000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                setRequestProperty("User-Agent", "ClearTune/${BuildConfig.VERSION_NAME}")
            }
            try {
                if (connection.responseCode !in 200..299) error("GitHub 暂时无法访问")
                val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                val version = json.getString("tag_name")
                UpdateRelease(
                    version = version,
                    name = json.optString("name", version),
                    pageUrl = json.getString("html_url"),
                    notes = json.optString("body"),
                    newer = VersionComparator.isNewer(version, BuildConfig.VERSION_NAME),
                )
            } finally {
                connection.disconnect()
            }
        }
    }

    private companion object {
        const val LATEST_RELEASE_URL = "https://api.github.com/repos/itgou1/ClearTune/releases/latest"
    }
}
