package de.tromuwelle.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Server-backed genres and tags for private media, with editable aliases and rules. */
@Composable
fun AdultTaxonomyScreen(session: TromuwelleSession) {
    val api = remember(session) { TromuwelleApi(session) }
    val scope = rememberCoroutineScope()
    var definitions by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var selectedId by remember { mutableIntStateOf(0) }
    var name by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf("genre") }
    var aliases by remember { mutableStateOf("") }
    var enabled by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableIntStateOf(0) }

    fun load() {
        scope.launch {
            try {
                definitions = withContext(Dispatchers.IO) { api.adultTaxonomy().objects() }
            } catch (e: Exception) { error = e.message ?: "Kategorien konnten nicht geladen werden." }
        }
    }
    LaunchedEffect(Unit) { load() }
    fun edit(row: JSONObject?) {
        selectedId = row?.optInt("id") ?: 0
        name = row?.optString("name").orEmpty()
        kind = row?.optString("kind").orEmpty().ifBlank { "genre" }
        aliases = row?.optJSONArray("aliases")?.let { data ->
            (0 until data.length()).joinToString(", ") { data.optString(it) }
        }.orEmpty()
        enabled = row?.optBoolean("enabled") ?: true
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Genres und Schlagwörter", style = MaterialTheme.typography.titleLarge)
        Text("Kategorien stammen vom Server. Änderungen können eine erneute Indizierung auslösen.",
            style = MaterialTheme.typography.bodySmall)
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        OutlinedTextField(name, { name = it }, label = { Text("Name") },
            modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = kind == "genre", onClick = { kind = "genre" },
                label = { Text("Genre") })
            FilterChip(selected = kind == "tag", onClick = { kind = "tag" },
                label = { Text("Schlagwort") })
        }
        OutlinedTextField(aliases, { aliases = it }, label = { Text("Synonyme (Komma-getrennt)") },
            modifier = Modifier.fillMaxWidth())
        Row {
            Checkbox(checked = enabled, onCheckedChange = { enabled = it })
            Text("Aktiv", Modifier.padding(top = 12.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !busy && name.trim().isNotBlank(), onClick = {
                val entry = selectedId
                val value = name.trim()
                val type = kind
                val synonyms = aliases.split(",").map { it.trim() }.filter { it.isNotBlank() }.distinct()
                val on = enabled
                busy = true
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) { api.saveAdultTaxonomy(entry, value, type, synonyms, on) }
                        error = ""
                        edit(null)
                        load()
                    } catch (e: Exception) { error = e.message ?: "Kategorie konnte nicht gespeichert werden." }
                    finally { busy = false }
                }
            }) { Text(if (selectedId == 0) "Hinzufügen" else "Änderungen speichern") }
            if (selectedId != 0) {
                OutlinedButton(onClick = { edit(null) }) { Text("Abbrechen") }
            }
        }
        HorizontalDivider()
        Text(definitions.size.toString() + " Kategorien / Schlagwörter",
            style = MaterialTheme.typography.titleMedium)
        Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
            definitions.forEach { item ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text(item.optString("name"), style = MaterialTheme.typography.titleSmall)
                        Text((if (item.optString("kind") == "genre") "Genre" else "Tag") +
                            if (item.optBoolean("enabled")) " · aktiv" else " · deaktiviert",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(enabled = !busy, onClick = { edit(item) }) { Text("Ändern") }
                    TextButton(enabled = !busy, onClick = {
                        confirmDelete = item.optInt("id")
                    }) { Text("Löschen") }
                }
                HorizontalDivider()
            }
        }
    }

    if (confirmDelete != 0) {
        AlertDialog(onDismissRequest = { confirmDelete = 0 },
            title = { Text("Kategorie löschen?") },
            text = { Text("Die Definition wird auf dem Server entfernt und die automatische Zuordnung später aktualisiert.") },
            confirmButton = {
                Button(onClick = {
                    val id = confirmDelete
                    confirmDelete = 0
                    busy = true
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { api.deleteAdultTaxonomy(id) }
                            edit(null)
                            load()
                            error = ""
                        } catch (e: Exception) { error = e.message ?: "Löschen fehlgeschlagen." }
                        finally { busy = false }
                    }
                }) { Text("Löschen") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = 0 }) { Text("Abbrechen") } })
    }
}
