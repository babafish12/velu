package de.voci.features.settings

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.voci.legal.LicenseDocumentViewModel
import de.voci.legal.LicenseListViewModel
import de.voci.legal.LicenseState
import de.voci.ui.components.InlineError
import de.voci.ui.components.Page
import de.voci.ui.components.SettingRow
import de.voci.ui.components.VociButton
import de.voci.ui.theme.VociTokens

@Composable
fun LicenseListScreen(
    back: () -> Unit,
    open: (String) -> Unit,
    openNotices: () -> Unit,
    model: LicenseListViewModel = viewModel(),
) {
    val state by model.state.collectAsStateWithLifecycle()
    Page("Lizenzen", back) {
        SettingRow("Dritthinweise (vollständig)", "Alle Lizenz- und Urheberrechtshinweise", openNotices)
        when (val current = state) {
            LicenseState.Loading -> Text("Lizenzen werden geladen …")
            is LicenseState.Failed -> InlineError(current.message, "Erneut versuchen", model::retry)
            is LicenseState.Ready -> {
                // Own project license is sorted first by the local repository.
                current.value.forEach { entry ->
                    key(entry.id) { SettingRow(entry.title, entry.kindLabel) { open(entry.id) } }
                }
            }
        }
    }
}

@Composable
fun LicenseTextScreen(licenseId: String, back: () -> Unit) = LicenseDocumentRoute(licenseId, back)

@Composable
fun LicenseNoticesScreen(back: () -> Unit) = LicenseDocumentRoute(null, back)

@Composable
private fun LicenseDocumentRoute(licenseId: String?, back: () -> Unit) {
    val application = LocalContext.current.applicationContext as Application
    // Isolate collection as well as Page's saved scroll and selection: a changed argument
    // must not render the previous collector's value even for a single composition.
    key(licenseId) {
        val factory = remember(application, licenseId) { LicenseDocumentViewModel.factory(application, licenseId) }
        val model: LicenseDocumentViewModel = viewModel(key = licenseId ?: "license-notices", factory = factory)
        val state by model.state.collectAsStateWithLifecycle()
        val document = when (val current = state) {
            is LicenseState.Ready -> current.value
            else -> null
        }
        Page(document?.title ?: if (licenseId == null) "Dritthinweise" else "Lizenztext", back) {
            when (val current = state) {
                LicenseState.Loading -> Text("Text wird geladen …")
                is LicenseState.Failed -> InlineError(current.message, "Erneut versuchen", model::retry)
                is LicenseState.Ready -> {
                    val value = current.value
                    Text(value.kindLabel, color = VociTokens.secondary)
                    SelectionContainer {
                        Column {
                            value.components.forEach { component ->
                                Text(component, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                    @Suppress("DEPRECATION")
                    val clipboard = LocalClipboardManager.current
                    VociButton("Gesamten Text kopieren", { clipboard.setText(AnnotatedString(value.text)) })
                    SelectionContainer {
                        Column(Modifier.fillMaxWidth()) {
                            value.blocks.forEach { block ->
                                Text(block, Modifier.fillMaxWidth().testTag("license-text"),
                                    style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        }
    }
}
