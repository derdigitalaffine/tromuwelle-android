package de.tromuwelle.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/**
 * A conscious trust decision for one particular server certificate.
 *
 * The inspection does NOT send authentication details. Pin approval is user-initiated,
 * never automatic, and may be revoked if the device or server changes owners.
 */
@Composable
fun CertificateTrustControl(address: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val store = remember(context) {
        (context.applicationContext as TromuwelleApplication).tlsTrust
    }
    val scope = rememberCoroutineScope()
    var inspected by remember(address) { mutableStateOf<InspectedCertificate?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember(address) { mutableStateOf("") }
    var changed by remember { mutableIntStateOf(0) }
    val pin = remember(address, changed) {
        runCatching { store.existing(address) }.getOrNull()
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        OutlinedButton(
            enabled = !busy && address.trim().startsWith("https://", ignoreCase = true),
            onClick = {
                scope.launch {
                    busy = true
                    error = ""
                    inspected = try {
                        withContext(Dispatchers.IO) { store.inspect(address) }
                    } catch (e: Exception) {
                        error = "Zertifikatsprüfung fehlgeschlagen: " +
                            tlsConnectionDiagnostic(e)
                        null
                    } finally { busy = false }
                }
            }
        ) {
            Text(if (busy) "TLS-Zertifikat wird gelesen …"
                else if (pin == null) "Selbstsigniertes Zertifikat prüfen"
                else "Freigegebenes Zertifikat anzeigen / ändern")
        }
        if (pin != null) {
            Text("Für diesen Server ist ein Zertifikat explizit freigegeben.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary)
            TextButton(onClick = {
                scope.launch {
                    withContext(Dispatchers.IO) { store.revoke(address) }
                    (context.applicationContext as TromuwelleApplication).session.client
                        .connectionPool.evictAll()
                    changed++
                    inspected = null
                }
            }) { Text("Zertifikatsfreigabe widerrufen") }
        }
        if (error.isNotBlank()) Text(error,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error)
    }

    inspected?.let { certificate ->
        val different = pin != null && pin != certificate.sha256
        val expired = certificate.expires < System.currentTimeMillis()
        AlertDialog(onDismissRequest = { inspected = null },
            title = { Text(if (different) "Achtung: Zertifikat geändert!"
                else "Serverzertifikat freigeben?") },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Server: " + certificate.key)
                    Text("Ausgestellt für: " + certificate.subject,
                        style = MaterialTheme.typography.bodySmall)
                    Text("Aussteller: " + certificate.issuer,
                        style = MaterialTheme.typography.bodySmall)
                    Text("Gültig bis: " + DateFormat.getDateTimeInstance()
                        .format(Date(certificate.expires)),
                        style = MaterialTheme.typography.bodySmall)
                    if (expired) Text("Achtung: Das Zertifikat ist abgelaufen.",
                        color = MaterialTheme.colorScheme.error)
                    Text("SHA-256-Fingerabdruck", style = MaterialTheme.typography.titleSmall)
                    SelectionContainer {
                        Text(certificate.sha256, fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall)
                    }
                    if (different) {
                        Text("Das neue Zertifikat weicht vom bisher freigegebenen ab. " +
                            "Änderung unbedingt vor einer neuen Freigabe beim Serverbetreiber prüfen.",
                            color = MaterialTheme.colorScheme.error)
                    }
                    Text("Vergleiche den Fingerabdruck über einen unabhängigen, sicheren Weg " +
                        "mit dem echten Server. Diese Prüfung hat NUR das öffentliche Zertifikat " +
                        "ausgelesen – kein Passwort übertragen. Nach der Freigabe wird ausschließlich " +
                        "dieses Zertifikat akzeptiert; unbekannte Zertifikate bleiben gesperrt.",
                        style = MaterialTheme.typography.bodyMedium)
                }
            },
            confirmButton = {
                Button(onClick = {
                    scope.launch {
                        busy = true
                        try {
                            withContext(Dispatchers.IO) { store.accept(certificate) }
                            (context.applicationContext as TromuwelleApplication).session.client
                                .connectionPool.evictAll()
                            changed++
                            inspected = null
                            error = ""
                        } catch (e: Exception) {
                            error = e.message ?: "Freigabe fehlgeschlagen."
                        } finally { busy = false }
                    }
                }) { Text(if (different) "Neues Zertifikat ausdrücklich freigeben"
                         else "Diesem Zertifikat vertrauen") }
            },
            dismissButton = {
                TextButton(onClick = { inspected = null }) { Text("Abbrechen") }
            })
    }
}
