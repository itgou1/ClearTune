package com.cleartune.data.local

import android.Manifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class AudioPermissionPolicyTest {
    @Test
    fun legacy_android_uses_scoped_external_storage_permission() {
        assertEquals(Manifest.permission.READ_EXTERNAL_STORAGE, AudioPermissionPolicy.requiredPermission(26))
        assertEquals(Manifest.permission.READ_EXTERNAL_STORAGE, AudioPermissionPolicy.requiredPermission(32))
    }

    @Test
    fun android_13_and_newer_use_media_audio_permission() {
        assertEquals(Manifest.permission.READ_MEDIA_AUDIO, AudioPermissionPolicy.requiredPermission(33))
        assertEquals(Manifest.permission.READ_MEDIA_AUDIO, AudioPermissionPolicy.requiredPermission(37))
    }

    @Test
    fun merged_manifest_declares_the_platform_split_audio_permissions() {
        val mergedManifest = File("build/intermediates/merged_manifest/debug/processDebugManifest/AndroidManifest.xml")
        assertTrue("Debug merged manifest was not generated", mergedManifest.isFile)
        val permissionNodes = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(mergedManifest)
            .getElementsByTagName("uses-permission")
        val permissions = buildMap {
            repeat(permissionNodes.length) { index ->
                val attributes = permissionNodes.item(index).attributes
                put(
                    attributes.getNamedItem("android:name").nodeValue,
                    attributes.getNamedItem("android:maxSdkVersion")?.nodeValue,
                )
            }
        }

        assertEquals(
            setOf(Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.READ_EXTERNAL_STORAGE),
            permissions.keys,
        )
        assertEquals(null, permissions[Manifest.permission.READ_MEDIA_AUDIO])
        assertEquals("32", permissions[Manifest.permission.READ_EXTERNAL_STORAGE])
    }
}
