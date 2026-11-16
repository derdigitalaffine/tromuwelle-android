package de.tromuwelle.android

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp

/** High-contrast, responsive sign-in flow; existing TLS and setup paths are preserved. */
@Composable
fun TromuConnectionScreen(
    server: String, onServer: (String) -> Unit,
    username: String, onUsername: (String) -> Unit,
    password: String, onPassword: (String) -> Unit,
    setupRequired: Boolean, serverHasAccounts: Boolean?,
    setupToken: String, onSetupToken: (String) -> Unit,
    previousAdminPassword: String, onPreviousAdminPassword: (String) -> Unit,
    connecting: Boolean, message: String,
    onCheckServer: () -> Unit, onSubsonic: () -> Unit,
    onLogin: () -> Unit, onAppearance: () -> Unit
) {
    var passwordVisible by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    Box(Modifier.fillMaxSize().background(colors.background)) {
        Column(
            Modifier.align(Alignment.TopCenter).widthIn(max = 600.dp)
                .fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(19.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(42.dp).clip(RoundedCornerShape(13.dp))
                        .background(Brush.linearGradient(
                            listOf(colors.primary, colors.tertiary))),
                        contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.GraphicEq, contentDescription = null,
                            tint = colors.onPrimary)
                    }
                    Spacer(Modifier.width(10.dp))
                    Text("TROMUWELLE", style = MaterialTheme.typography.titleMedium,
                        color = colors.onBackground, fontWeight = FontWeight.Black)
                }
                TextButton(onClick = onAppearance) {
                    Icon(Icons.Rounded.Palette, contentDescription = null)
                    Spacer(Modifier.width(5.dp))
                    Text("Design")
                }
            }

            Surface(Modifier.fillMaxWidth(), color = colors.primaryContainer,
                shape = RoundedCornerShape(26.dp)) {
                Column(Modifier.padding(22.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Rounded.Waves, contentDescription = null,
                        tint = colors.onPrimaryContainer,
                        modifier = Modifier.size(30.dp))
                    Text("Deine Medien. Dein Zuhause.",
                        style = MaterialTheme.typography.headlineMedium,
                        color = colors.onPrimaryContainer, fontWeight = FontWeight.ExtraBold)
                    Text("Musik, Podcasts, Radio, Filme und Live-TV an einem Ort.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onPrimaryContainer)
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Mit Server verbinden",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold, color = colors.onBackground)
                Text("Melde dich sicher bei deinem Tromuwelle-Server an.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant)
            }
            Surface(color = colors.surface, tonalElevation = 2.dp,
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(13.dp)) {
                    OutlinedTextField(value = server, onValueChange = onServer,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("HTTPS-Serveradresse") },
                        placeholder = { Text("https://server:8097") },
                        leadingIcon = { Icon(Icons.Rounded.Dns, contentDescription = null) },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        singleLine = true, shape = RoundedCornerShape(14.dp))
                    CertificateTrustControl(address = server)
                    OutlinedTextField(value = username, onValueChange = onUsername,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Benutzername") },
                        leadingIcon = {
                            Icon(Icons.Rounded.PersonOutline, contentDescription = null)
                        },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        supportingText = {
                            Text(when (serverHasAccounts) {
                                true -> "Konto deiner Tromuwelle-Benutzerverwaltung"
                                false -> "Alter Passwortmodus: Dieses Feld leer lassen"
                                null -> "Nur Passwort eingerichtet? Benutzername leer lassen"
                            })
                        },
                        singleLine = true, shape = RoundedCornerShape(14.dp))
                    OutlinedTextField(value = password, onValueChange = onPassword,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Passwort") },
                        leadingIcon = { Icon(Icons.Rounded.LockOutline, contentDescription = null) },
                        trailingIcon = {
                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                Icon(if (passwordVisible) Icons.Rounded.VisibilityOff
                                    else Icons.Rounded.Visibility,
                                    contentDescription = if (passwordVisible)
                                        "Passwort verbergen" else "Passwort anzeigen")
                            }
                        },
                        visualTransformation = if (passwordVisible)
                            VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        singleLine = true, shape = RoundedCornerShape(14.dp))
                    OutlinedButton(onClick = onCheckServer, enabled = !connecting &&
                        server.startsWith("https://"),
                        modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Rounded.VerifiedUser, contentDescription = null,
                            modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(7.dp))
                        Text("Verbindung und Einrichtung prüfen")
                    }
                    if (setupRequired) {
                        HorizontalDivider()
                        Text("Administratorkonto einrichten",
                            style = MaterialTheme.typography.titleMedium,
                            color = colors.onSurface)
                        OutlinedTextField(value = setupToken, onValueChange = onSetupToken,
                            modifier = Modifier.fillMaxWidth(), label = { Text("Einrichtungstoken") },
                            visualTransformation = PasswordVisualTransformation(),
                            shape = RoundedCornerShape(14.dp))
                        OutlinedTextField(value = previousAdminPassword,
                            onValueChange = onPreviousAdminPassword,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Bisheriges Adminpasswort (optional)") },
                            visualTransformation = PasswordVisualTransformation(),
                            shape = RoundedCornerShape(14.dp))
                        Text("Das neue Administratorkennwort benötigt mindestens 12 Zeichen.",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant)
                    }
                    Button(onClick = onLogin, enabled = !connecting,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                        if (connecting) {
                            CircularProgressIndicator(Modifier.size(18.dp),
                                strokeWidth = 2.dp, color = colors.onPrimary)
                        } else {
                            Icon(Icons.Rounded.Login, contentDescription = null,
                                modifier = Modifier.size(19.dp))
                        }
                        Spacer(Modifier.width(9.dp))
                        Text(if (connecting) "Verbindung wird aufgebaut" else
                            if (setupRequired) "Administratorkonto einrichten" else "Anmelden")
                    }
                    if (message.isNotBlank()) {
                        val informational = message.startsWith("Server erreichbar")
                        Surface(color = if (informational) colors.primaryContainer
                            else colors.errorContainer, shape = RoundedCornerShape(12.dp)) {
                            Row(Modifier.fillMaxWidth().padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Icon(if (informational) Icons.Rounded.CheckCircle
                                    else Icons.Rounded.Info,
                                    contentDescription = null,
                                    tint = if (informational) colors.onPrimaryContainer
                                        else colors.onErrorContainer)
                                Spacer(Modifier.width(8.dp))
                                Text(message, style = MaterialTheme.typography.bodySmall,
                                    color = if (informational) colors.onPrimaryContainer
                                        else colors.onErrorContainer)
                            }
                        }
                    }
                }
            }
            OutlinedButton(onClick = onSubsonic, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.LibraryMusic, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Externen Musikserver verbinden")
            }
            Row(verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Icon(Icons.Rounded.Security, contentDescription = null,
                    tint = colors.primary, modifier = Modifier.size(19.dp))
                Text("Selbstsigniertes Zertifikat? Prüfe vor einer Freigabe den " +
                    "SHA-256-Fingerabdruck mit deinem Server oder installiere " +
                    "deine vertrauenswürdige CA in Android. Die Verschlüsselung bleibt aktiv.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant)
            }
            Spacer(Modifier.height(14.dp))
        }
    }
}
