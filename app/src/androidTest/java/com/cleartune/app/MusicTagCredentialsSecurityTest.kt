package com.cleartune.app

import androidx.test.platform.app.InstrumentationRegistry
import com.cleartune.core.datastore.MusicTagSettingsStore
import com.cleartune.core.model.*
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MusicTagCredentialsSecurityTest {
    @Test fun sshCredentialsRoundTripThroughAndroidKeystoreAndAreRemoved() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val account = "ssh-security-test-${UUID.randomUUID()}"
        val preferences = context.getSharedPreferences("music_tag_connections", 0)
        val store = MusicTagSettingsStore(context)
        val settings = MusicTagSettings("http://example.invalid:8002", "webtag-test", "webtag-test-secret",
            connectionMode = MusicTagConnectionMode.SSH,
            ssh = MusicTagSshSettings("ssh.example.invalid", 22, "ssh-test", password = "ssh-test-secret",
                privateKey = "private-key-test-content", passphrase = "private-key-test-passphrase"))
        try {
            store.save(account, settings)
            assertEquals(settings, store.read(account))
            val firstCiphertext = requireNotNull(preferences.getString(account, null))
            listOf(settings.password, settings.ssh.password, settings.ssh.privateKey, settings.ssh.passphrase).forEach {
                assertFalse(firstCiphertext.contains(it))
            }
            store.save(account, settings)
            assertNotEquals(firstCiphertext, preferences.getString(account, null))
            store.clear(account)
            assertNull(preferences.getString(account, null))
            assertFalse(store.read(account).configured)
        } finally { store.clear(account) }
    }

    @Test fun bundledEd25519ImplementationInitializesOnAndroid() {
        val implementation = Class.forName("com.jcraft.jsch.bc.SignatureEd25519")
        val signature = implementation.getDeclaredConstructor().newInstance()
        implementation.getMethod("init").invoke(signature)
    }
}
