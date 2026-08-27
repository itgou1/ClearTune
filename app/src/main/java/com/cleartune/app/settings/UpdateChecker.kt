package com.cleartune.app.settings

import com.cleartune.app.BuildConfig
import com.cleartune.core.model.VersionComparator
import java.net.HttpURLConnection
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

data class UpdateRelease(
    val versionCode: Int?,
    val version: String,
    val name: String,
    val pageUrl: String,
    val notes: String,
    val apkDownloadUrl: String?,
    val apkSizeBytes: Long?,
    val sha256: String?,
    val newer: Boolean,
) {
    val identity: String
        get() = "${versionCode ?: "legacy"}:$version"
}

@Singleton
class UpdateChecker @Inject constructor() {
    suspend fun check(): Result<UpdateRelease> = withContext(Dispatchers.IO) {
        runCatching {
            val releaseJson = get(LATEST_RELEASE_URL)
            val releaseObject = JSON.parseToJsonElement(releaseJson).jsonObject
            val assets = releaseObject["assets"]?.jsonArray ?: JsonArray(emptyList())
            val manifestAsset = assets.objects()
                .firstOrNull { it.string("name") == UPDATE_MANIFEST_ASSET_NAME }
            val manifestJson = manifestAsset
                ?.string("browser_download_url")
                ?.takeIf(::isTrustedReleaseAssetUrl)
                ?.let(::get)
            parseRelease(
                releaseJson = releaseJson,
                manifestJson = manifestJson,
                currentVersionCode = BuildConfig.VERSION_CODE,
                currentVersionName = BuildConfig.VERSION_NAME,
            )
        }
    }

    private fun get(url: String): String {
        require(url.startsWith("https://")) { "Update requests must use HTTPS" }
        val connection = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
            connectTimeout = NETWORK_TIMEOUT_MS
            readTimeout = NETWORK_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", GITHUB_API_VERSION)
            setRequestProperty("User-Agent", "ClearTune/${BuildConfig.VERSION_NAME}")
        }
        return try {
            if (connection.responseCode !in 200..299) {
                error("GitHub Releases returned HTTP ${connection.responseCode}")
            }
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val LATEST_RELEASE_URL = "https://api.github.com/repos/itgou1/ClearTune/releases/latest"
        const val UPDATE_MANIFEST_ASSET_NAME = "update.json"
        const val GITHUB_API_VERSION = "2022-11-28"
        const val NETWORK_TIMEOUT_MS = 10_000
    }
}

internal fun parseRelease(
    releaseJson: String,
    manifestJson: String?,
    currentVersionCode: Int,
    currentVersionName: String,
): UpdateRelease {
    val release = JSON.parseToJsonElement(releaseJson).jsonObject
    val tag = release.requiredString("tag_name")
    val pageUrl = release.requiredString("html_url").also {
        require(isTrustedReleasePageUrl(it)) { "Untrusted release page URL" }
    }
    val assets = release["assets"]?.jsonArray ?: JsonArray(emptyList())
    val manifest = manifestJson?.let { JSON.parseToJsonElement(it).jsonObject }

    val manifestVersionCode = manifest?.int("versionCode")
    val manifestVersionName = manifest?.string("versionName")
    val apkAssetName = manifest?.string("apkAssetName")
    val sha256 = manifest?.string("sha256")?.lowercase()

    if (manifest != null) {
        require(manifest.int("schemaVersion") == UPDATE_MANIFEST_SCHEMA_VERSION) {
            "Unsupported update manifest schema"
        }
        require(manifestVersionCode != null && manifestVersionCode > 0) { "Invalid versionCode" }
        require(!manifestVersionName.isNullOrBlank()) { "Invalid versionName" }
        require(normalizeVersion(tag) == normalizeVersion(manifestVersionName)) {
            "Release tag and update manifest version do not match"
        }
        require(!apkAssetName.isNullOrBlank()) { "Missing APK asset name" }
        require(sha256?.matches(SHA_256_PATTERN) == true) { "Invalid APK SHA-256" }
    }

    val apkAsset = apkAssetName?.let { expected ->
        assets.objects().firstOrNull { it.string("name") == expected }
    }
    val apkDownloadUrl = apkAsset
        ?.string("browser_download_url")
        ?.takeIf(::isTrustedReleaseAssetUrl)
    if (manifest != null) {
        require(apkAsset != null) { "APK asset declared by update manifest was not found" }
        require(apkDownloadUrl != null) { "Untrusted APK download URL" }
    }
    val resolvedVersion = manifestVersionName ?: tag
    val newer = manifestVersionCode?.let { it > currentVersionCode }
        ?: VersionComparator.isNewer(resolvedVersion, currentVersionName)

    return UpdateRelease(
        versionCode = manifestVersionCode,
        version = resolvedVersion,
        name = release.string("name").orEmpty().ifBlank { tag },
        pageUrl = pageUrl,
        notes = release.string("body").orEmpty(),
        apkDownloadUrl = apkDownloadUrl,
        apkSizeBytes = apkAsset?.long("size"),
        sha256 = sha256,
        newer = newer,
    )
}

private val JSON = Json { ignoreUnknownKeys = true }
private val SHA_256_PATTERN = Regex("^[a-f0-9]{64}$")
private const val UPDATE_MANIFEST_SCHEMA_VERSION = 1

private fun JsonArray.objects(): List<JsonObject> = mapNotNull { element ->
    runCatching { element.jsonObject }.getOrNull()
}

private fun JsonObject.string(name: String): String? =
    get(name)?.jsonPrimitive?.contentOrNull

private fun JsonObject.requiredString(name: String): String =
    requireNotNull(string(name)?.takeIf(String::isNotBlank)) { "Missing $name" }

private fun JsonObject.int(name: String): Int? = get(name)?.jsonPrimitive?.intOrNull

private fun JsonObject.long(name: String): Long? = get(name)?.jsonPrimitive?.longOrNull

private fun normalizeVersion(version: String): String = version.trim().trimStart('v', 'V')

private fun isTrustedReleasePageUrl(url: String): Boolean = runCatching {
    val uri = URI(url)
    uri.scheme == "https" &&
        uri.host.equals("github.com", ignoreCase = true) &&
        uri.path.startsWith("/itgou1/ClearTune/releases/")
}.getOrDefault(false)

private fun isTrustedReleaseAssetUrl(url: String): Boolean = runCatching {
    val uri = URI(url)
    uri.scheme == "https" &&
        uri.host.equals("github.com", ignoreCase = true) &&
        uri.path.startsWith("/itgou1/ClearTune/releases/download/")
}.getOrDefault(false)
