package de.voci

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import de.voci.update.AndroidUpdateHost
import de.voci.update.UpdateFiles
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** Interactive Parent-owned acceptance only; process death is not a JUnit PASS. */
class R7InstallerAcceptanceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun interactiveNativeInstallerAcceptance() {
        assumeTrue("Explicit interactive installer opt-in required",
            InstrumentationRegistry.getArguments().getString("r7_interactive_installer_acceptance") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = compose.activity
        assertEquals("de.voci", activity.packageName)
        val source = File(activity.applicationInfo.sourceDir)
        assertTrue("Complete installed APK required", source.isFile && source.length() > 0)
        val files = UpdateFiles(activity.cacheDir)
        var owned = files.fresh(source.length())
        var installerLaunched = false
        val host = AndroidUpdateHost(activity,
            settings = { activity.startActivity(it) },
            installer = {
                activity.startActivity(it)
                installerLaunched = true
            })
        fun status(state: String, hash: String? = null) {
            instrumentation.sendStatus(2, Bundle().apply {
                putString("r7_interactive_installer", state)
                if (hash != null) {
                    putString("apk_basename", owned.name)
                    putString("apk_sha256", hash)
                    putLong("apk_size", owned.length())
                }
            })
        }
        try {
            source.inputStream().use { input -> owned.outputStream().use { input.copyTo(it) } }
            assertEquals(source.length(), owned.length())
            val hash = sha256(source)
            assertEquals(hash, sha256(owned))
            owned = files.promote(owned)
            assertEquals(source.length(), owned.length())
            assertEquals(hash, sha256(owned))
            compose.runOnUiThread { host.openSettings() }
            status("settings_opened")
            runBlocking {
                withTimeout(120_000) {
                    while (true) {
                        var ready = false
                        compose.runOnUiThread {
                            ready = host.canInstall() && activity.lifecycle.currentState == Lifecycle.State.RESUMED
                        }
                        if (ready) break
                        delay(100)
                    }
                }
            }
            compose.runOnUiThread { host.install(owned) }
            status("installer_launched", hash)
            // Android reads the URI asynchronously. Parent owns this exact copy after launch.
            runBlocking { delay(120_000) }
            fail("External native installation did not terminate instrumentation within 120s; Parent must assess UI, installed bytes and data preservation independently")
        } finally {
            if (!installerLaunched && owned.exists()) files.discard(owned)
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
