package de.voci

import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.content.FileProvider
import de.voci.update.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Real local PackageManager/FileProvider, captured intents only. Does not open Settings,
 * grant permissions or install anything; full external platform UI remains Parent acceptance.
 */
class R7UpdatePlatformTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun settingsIntentIsPackageSpecificAndProviderIsNarrow() {
        val activity = compose.activity
        var captured: Intent? = null
        val host = AndroidUpdateHost(activity, { captured = it }, { error("unexpected installer") })
        compose.runOnIdle { host.openSettings() }
        assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, captured!!.action)
        assertEquals("package:${activity.packageName}", captured!!.data.toString())
        @Suppress("DEPRECATION")
        val provider = activity.packageManager.resolveContentProvider("${activity.packageName}.updates", PackageManager.GET_META_DATA)!!
        assertFalse(provider.exported)
        assertTrue(provider.grantUriPermissions)
        val outside = File(activity.filesDir, "r7-never-created.apk")
        assertThrows(IllegalArgumentException::class.java) {
            FileProvider.getUriForFile(activity, "${activity.packageName}.updates", outside)
        }
    }
    @Test fun actualSourceCopyUsesContentUriReadGrantOrTruthfullyRefusesMissingPermission() {
        val activity = compose.activity
        val files = UpdateFiles(activity.cacheDir)
        val source = File(activity.applicationInfo.sourceDir)
        val part = files.fresh(source.length())
        var owned: File = part
        try {
            source.copyTo(part, overwrite = true)
            owned = files.promote(part)
            val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.updates", owned)
            assertEquals("content", uri.scheme)
            activity.contentResolver.openInputStream(uri)!!.use { assertEquals('P'.code, it.read()) }
            var captured: Intent? = null
            val host = AndroidUpdateHost(activity, { error("unexpected settings") }, { captured = it })
            compose.runOnIdle {
                if (host.canInstall()) {
                    host.install(owned)
                    @Suppress("DEPRECATION")
                    assertEquals(Intent.ACTION_INSTALL_PACKAGE, captured!!.action)
                    assertEquals(uri, captured!!.data)
                    assertTrue(captured!!.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
                    assertEquals(uri, captured!!.clipData!!.getItemAt(0).uri)
                    @Suppress("DEPRECATION")
                    assertTrue(captured!!.getBooleanExtra(Intent.EXTRA_RETURN_RESULT, false))
                } else {
                    val error = assertThrows(UpdateFailure::class.java) { host.install(owned) }
                    assertEquals(UpdateProblem.PERMISSION, error.problem)
                    assertNull(captured)
                }
            }
        } finally {
            // No external reader was launched: these copies belong exclusively to this test.
            if (owned.exists()) files.discard(owned)
            if (part.exists()) files.discard(part)
        }
    }
}
