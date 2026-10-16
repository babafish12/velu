package de.voci.update

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

/** Only normal user-owned platform UIs; ActivityResult launchers are registered by MainActivity. */
class AndroidUpdateHost(
    private val activity: Activity,
    private val settings: (Intent) -> Unit,
    private val installer: (Intent) -> Unit
) : UpdateHost {
    override fun canInstall() = activity.packageManager.canRequestPackageInstalls()
    override fun openSettings() {
        launch { settings(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${activity.packageName}"))) }
    }
    @Suppress("DEPRECATION")
    override fun install(file: File) {
        UpdateFiles(activity.cacheDir).requireOwned(file)
        requireUpdate(canInstall(), UpdateProblem.PERMISSION)
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.updates", file)
        requireUpdate(uri.scheme == "content", UpdateProblem.PLATFORM)
        val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .putExtra(Intent.EXTRA_RETURN_RESULT, true)
        intent.clipData = android.content.ClipData.newRawUri("Velu update", uri)
        launch { installer(intent) }
    }
    private fun launch(block: () -> Unit) {
        try { block() }
        catch (_: android.content.ActivityNotFoundException) { throw UpdateFailure(UpdateProblem.PLATFORM) }
        catch (_: SecurityException) { throw UpdateFailure(UpdateProblem.PLATFORM) }
    }
}
