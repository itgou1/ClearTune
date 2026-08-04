package com.cleartune.app

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NetworkPolicyAndroidTest {
    @Test
    fun platformPermitsCleartextOnlySoThePerSourcePolicyCanApplyItsExplicitGate() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val info = context.packageManager.getApplicationInfo(context.packageName, 0)
        assertTrue(info.flags and ApplicationInfo.FLAG_USES_CLEARTEXT_TRAFFIC != 0)
    }
}
