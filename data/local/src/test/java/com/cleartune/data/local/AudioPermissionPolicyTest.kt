package com.cleartune.data.local

import android.Manifest
import org.junit.Assert.assertEquals
import org.junit.Test

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
}
