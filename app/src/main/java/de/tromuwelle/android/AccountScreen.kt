package de.tromuwelle.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Native equivalent of AccountAccess and SubsonicKeys in the private web portal. */
@Composable
fun AccountScreen(session: TromuwelleSession, onBack: () -> Unit, onSessionRevoked: () -> Unit) {
    val scope = rememberCoroutineScope()
    val api = remember(session) { TromuwelleApi(session) }
    val context = LocalContext.current
    var password by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var subsonicPassword by remember { mutableStateOf("") }
    var apiKeyLabel by remember { mutableStateOf("Musik-Client") }
    var secret by remember { mutableStateOf("") }
    var secretTitle by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var keys by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var confirmRevoke by remember { mutableIntStateOf(-1) }

    fun refreshKeys() {
        if (!session.account.managed) return
        scope.launch {
            try { keys = withContext(Dispatchers.IO) { api.apiKeys().objects() } }
            catch (e: Exception) { message = e.message ?: "API-Schlüssel nicht verfügbar." }
        }
    }
    fun runAction(action: suspend () -> Unit) {
        scope.launch {
            busy = true; message = ""
            try { action() } catch (e: Exception) { message = e.message ?: "Aktion fehlgeschlagen." }
            finally { busy = false }
        }
    }
    LaunchedEffect(session.account.id) { refreshKeys() }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Mein Konto", style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = { secret = ""; onBack() }) { Text("Schließen") }
        }
        Text(session.account.username.ifBlank { session.username } +
            if (session.account.isAdmin) " · Administrator" else " · Benutzer")
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (message.isNotBlank()) Text(message,
            color = if (message.contains("fehler", ignoreCase = true)) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.primary)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("Passwort ändern", style = MaterialTheme.typography.titleMedium)
                if (!session.account.isAdmin) {
                    OutlinedTextField(password, { password = it },
                        label = { Text("Bisheriges Passwort") }, visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth())
                }
                OutlinedTextField(newPassword, { newPassword = it },
                    label = { Text("Neues Passwort (mind. 12 Zeichen)") },
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(confirmPassword, { confirmPassword = it },
                    label = { Text("Neues Passwort bestätigen") },
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                Button(enabled = !busy && session.account.managed, onClick = {
                    if (newPassword.length < 12 || newPassword != confirmPassword) {
                        message = "Passwort zu kurz oder Bestätigung stimmt nicht überein."
                    } else runAction {
                        withContext(Dispatchers.IO) {
                            api.changePassword(session.account.id, newPassword, password)
                            session.logout()
                        }
                        newPassword = ""; confirmPassword = ""; password = ""
                        onSessionRevoked()
                    }
                }) { Text("Passwort ändern") }
                if (!session.account.managed) Text("Persönliche Passwortverwaltung ist nur mit Benutzerkonten verfügbar.")
            }
            if (session.account.managed) {
                item {
                    HorizontalDivider()
                    Text("Musik-Apps · OpenSubsonic", style = MaterialTheme.typography.titleMedium)
                    Text("Das App-Passwort ist vom Kontopasswort getrennt. Beim Erneuern verlieren vorhandene Musikclients ihren Zugriff.",
                        style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(subsonicPassword, { subsonicPassword = it },
                        visualTransformation = PasswordVisualTransformation(),
                        label = { Text("Kontopasswort zur Bestätigung") }, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = !busy && subsonicPassword.isNotBlank(), onClick = {
                            runAction {
                                val data = withContext(Dispatchers.IO) {
                                    api.subsonicCredential(subsonicPassword, false)
                                }
                                secretTitle = "Subsonic-App-Passwort"
                                secret = data.optString("app_password")
                                subsonicPassword = ""
                            }
                        }) { Text("Anzeigen") }
                        OutlinedButton(enabled = !busy && subsonicPassword.isNotBlank(), onClick = {
                            runAction {
                                val data = withContext(Dispatchers.IO) {
                                    api.subsonicCredential(subsonicPassword, true)
                                }
                                secretTitle = "Neues Subsonic-App-Passwort"
                                secret = data.optString("app_password")
                                subsonicPassword = ""
                            }
                        }) { Text("Erneuern") }
                    }
                    Text("Im OpenSubsonic-Client dieselbe HTTPS-Serveradresse und den Kontonamen verwenden.",
                        style = MaterialTheme.typography.bodySmall)
                }
                item {
                    HorizontalDivider()
                    Text("API-Schlüssel", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(apiKeyLabel, { apiKeyLabel = it }, label = { Text("Name des Schlüssels") },
                        modifier = Modifier.fillMaxWidth())
                    Button(enabled = !busy && apiKeyLabel.isNotBlank(), onClick = {
                        runAction {
                            val data = withContext(Dispatchers.IO) { api.addApiKey(apiKeyLabel) }
                            secretTitle = "Neuer API-Schlüssel (nur einmal sichtbar)"
                            secret = data.optString("token")
                            keys = withContext(Dispatchers.IO) { api.apiKeys().objects() }
                            message = "Schlüssel erstellt."
                        }
                    }) { Text("Neuen Schlüssel erstellen") }
                }
                items(keys, key = { it.optInt("id") }) { key ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text(key.optString("label"), style = MaterialTheme.typography.titleSmall)
                            Text("ID ${key.optInt("id")}", style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { confirmRevoke = key.optInt("id") }) { Text("Widerrufen") }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
    if (secret.isNotBlank()) {
        AlertDialog(onDismissRequest = { secret = "" }, title = { Text(secretTitle) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Einmalig anzeigen und sicher im Passwortmanager speichern.")
                    SelectionContainer { Text(secret) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText(secretTitle, secret))
                    secret = ""
                }) { Text("Kopieren und schließen") }
            }, dismissButton = { TextButton(onClick = { secret = "" }) { Text("Schließen") } })
    }
    if (confirmRevoke >= 0) {
        AlertDialog(onDismissRequest = { confirmRevoke = -1 }, title = { Text("API-Schlüssel widerrufen?") },
            text = { Text("Alle Clients mit diesem Schlüssel verlieren sofort den Zugriff.") },
            confirmButton = { Button(onClick = {
                val id = confirmRevoke; confirmRevoke = -1
                runAction {
                    withContext(Dispatchers.IO) { api.deleteApiKey(id) }
                    keys = withContext(Dispatchers.IO) { api.apiKeys().objects() }
                    message = "Schlüssel widerrufen."
                }
            }) { Text("Widerrufen") } },
            dismissButton = { TextButton(onClick = { confirmRevoke = -1 }) { Text("Abbrechen") } })
    }
}
