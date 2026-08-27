package com.cleartune.app.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {
    @Test
    fun manifestVersionCodeIsAuthoritative() {
        val release = parseRelease(
            releaseJson = releaseJson(tag = "v1.0.0"),
            manifestJson = manifestJson(versionCode = 3, versionName = "1.0.0"),
            currentVersionCode = 2,
            currentVersionName = "1.0.0",
        )

        assertTrue(release.newer)
        assertEquals(3, release.versionCode)
        assertEquals(1_234_567L, release.apkSizeBytes)
        assertEquals("a".repeat(64), release.sha256)
    }

    @Test
    fun manifestPreventsVersionNameFromOverridingVersionCode() {
        val release = parseRelease(
            releaseJson = releaseJson(tag = "v2.0.0"),
            manifestJson = manifestJson(versionCode = 2, versionName = "2.0.0"),
            currentVersionCode = 2,
            currentVersionName = "1.0.0",
        )

        assertFalse(release.newer)
    }

    @Test
    fun legacyReleaseFallsBackToSemanticVersion() {
        val release = parseRelease(
            releaseJson = releaseJson(tag = "v1.1.0", includeManifestAsset = false),
            manifestJson = null,
            currentVersionCode = 2,
            currentVersionName = "1.0.0",
        )

        assertTrue(release.newer)
        assertEquals(null, release.versionCode)
    }

    @Test
    fun rejectsManifestThatDoesNotMatchReleaseTag() {
        assertThrows(IllegalArgumentException::class.java) {
            parseRelease(
                releaseJson = releaseJson(tag = "v1.0.1"),
                manifestJson = manifestJson(versionCode = 3, versionName = "1.0.2"),
                currentVersionCode = 2,
                currentVersionName = "1.0.0",
            )
        }
    }

    @Test
    fun rejectsUntrustedReleasePage() {
        assertThrows(IllegalArgumentException::class.java) {
            parseRelease(
                releaseJson = releaseJson(tag = "v1.0.1", pageUrl = "https://example.com/release"),
                manifestJson = null,
                currentVersionCode = 2,
                currentVersionName = "1.0.0",
            )
        }
    }

    @Test
    fun rejectsManifestWithMissingApkAsset() {
        assertThrows(IllegalArgumentException::class.java) {
            parseRelease(
                releaseJson = releaseJson(tag = "v1.0.1", apkAssetName = "another.apk"),
                manifestJson = manifestJson(versionCode = 3, versionName = "1.0.1"),
                currentVersionCode = 2,
                currentVersionName = "1.0.0",
            )
        }
    }

    private fun releaseJson(
        tag: String,
        pageUrl: String = "https://github.com/itgou1/ClearTune/releases/tag/$tag",
        includeManifestAsset: Boolean = true,
        apkAssetName: String = "ClearTune-$tag.apk",
    ): String {
        val manifestAsset = if (includeManifestAsset) {
            """
                {
                  "name": "update.json",
                  "browser_download_url": "https://github.com/itgou1/ClearTune/releases/download/$tag/update.json",
                  "size": 512
                },
            """.trimIndent()
        } else {
            ""
        }
        return """
            {
              "tag_name": "$tag",
              "name": "ClearTune $tag",
              "html_url": "$pageUrl",
              "body": "Release notes",
              "assets": [
                $manifestAsset
                {
                  "name": "$apkAssetName",
                  "browser_download_url": "https://github.com/itgou1/ClearTune/releases/download/$tag/$apkAssetName",
                  "size": 1234567
                }
              ]
            }
        """.trimIndent()
    }

    private fun manifestJson(versionCode: Int, versionName: String): String = """
        {
          "schemaVersion": 1,
          "versionCode": $versionCode,
          "versionName": "$versionName",
          "apkAssetName": "ClearTune-v$versionName.apk",
          "sha256": "${"a".repeat(64)}"
        }
    """.trimIndent()
}
