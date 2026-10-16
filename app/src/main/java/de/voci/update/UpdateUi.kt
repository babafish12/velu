package de.voci.update

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun UpdateLayout(controller: UpdateController, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Box(Modifier.weight(1f).fillMaxWidth()) { content() }
        UpdateOverlay(controller)
    }
}

@Composable
fun UpdateOverlay(controller: UpdateController) {
    val state by controller.state.collectAsState()
    when (val s = state) {
        is UpdateState.Offer -> AlertDialog(
            onDismissRequest = controller::defer,
            title = { Text("Neue Version ${s.candidate.versionName}") },
            text = { Text("Velu aktualisieren? Der Download startet erst mit deiner Zustimmung. Android bestätigt die Installation separat.") },
            confirmButton = { TextButton(onClick = controller::consent) { Text("Herunterladen & installieren") } },
            dismissButton = { TextButton(onClick = controller::defer) { Text("Später") } }
        )
        is UpdateState.Downloading -> AlertDialog(
            onDismissRequest = controller::cancel,
            title = { Text("Update wird geladen") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LinearProgressIndicator(progress = { (s.bytes.toFloat() / s.candidate.sizeBytes).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                Text("${s.bytes / 1024} / ${s.candidate.sizeBytes / 1024} KiB")
            } },
            confirmButton = { TextButton(onClick = controller::cancel) { Text("Abbrechen") } }
        )
        is UpdateState.Verifying -> AlertDialog(
            onDismissRequest = controller::cancel,
            title = { Text("Update wird geprüft") },
            text = { LinearProgressIndicator(Modifier.fillMaxWidth()) },
            confirmButton = { TextButton(onClick = controller::cancel) { Text("Abbrechen") } }
        )
        is UpdateState.Permission -> AlertDialog(
            onDismissRequest = controller::cancel,
            title = { Text("Installation erlauben") },
            text = { Text("Erlaube Velu in den Android-Einstellungen, dieses Update zu installieren. Danach zurück zu Velu wechseln.") },
            confirmButton = { TextButton(onClick = controller::cancel) { Text("Abbrechen") } }
        )
        is UpdateState.Failed -> if (s.userInitiated) AlertDialog(
            onDismissRequest = controller::dismiss,
            title = { Text("Update nicht abgeschlossen") },
            text = { Text(s.problem.explanation()) },
            confirmButton = { TextButton(onClick = controller::retry) { Text("Erneut prüfen") } },
            dismissButton = { TextButton(onClick = controller::dismiss) { Text("Schließen") } }
        ) else UpdateNotice(s.problem.explanation(), controller)
        UpdateState.NoRelease -> UpdateNotice("Noch kein öffentlicher Release verfügbar.", controller)
        UpdateState.Cancelled -> UpdateNotice("Update nicht installiert oder abgebrochen.", controller)
        UpdateState.Installed -> UpdateNotice("Update installiert.", controller)
        // Current and in-flight metadata checks need no modal UI or interference with lessons.
        else -> Unit
    }
}

@Composable
private fun UpdateNotice(message: String, controller: UpdateController) {
    Box(Modifier.fillMaxWidth().safeDrawingPadding()) {
        Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(message, style = MaterialTheme.typography.bodySmall)
                Row {
                    TextButton(onClick = controller::retry) { Text("Erneut prüfen") }
                    TextButton(onClick = controller::dismiss) { Text("Schließen") }
                }
            }
        }
    }
}

private fun UpdateProblem.explanation() = when (this) {
    UpdateProblem.NO_RELEASE -> "Noch kein öffentlicher Release verfügbar."
    UpdateProblem.OFFLINE -> "Updateprüfung ohne Verbindung. Gespeicherte Lektionen bleiben verfügbar."
    UpdateProblem.QUOTA -> "GitHub begrenzt gerade Anfragen. Bitte später erneut prüfen."
    UpdateProblem.SERVER -> "Der Update-Dienst antwortet nicht korrekt. Bitte später erneut prüfen."
    UpdateProblem.INVALID -> "Die Update-Daten sind ungültig oder nicht mit diesem Gerät kompatibel."
    UpdateProblem.SPACE -> "Nicht genug freier Speicher für das Update. Speicher freigeben und erneut versuchen."
    UpdateProblem.VERIFICATION -> "Die Update-Datei passt nicht zur installierten App oder ist beschädigt."
    UpdateProblem.PERMISSION -> "Android hat die Installation nicht erlaubt. Du kannst es erneut versuchen."
    UpdateProblem.PLATFORM -> "Android-Einstellungen oder Paketinstallation konnten nicht geöffnet werden."
}
