package de.tromuwelle.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Cross-category search uses exactly the public-within-session backend endpoints.
 * Private/adult media are deliberately not searched implicitly.
 */
@Composable
fun StreamGlobalSearch(session: TromuwelleSession, onBack: () -> Unit,
                       onOpen: (LibrarySection, LibraryEntry) -> Unit,
                       onSection: (LibrarySection) -> Unit) {
    var query by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<Map<LibrarySection, List<LibraryEntry>>>(emptyMap()) }
    var failed by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(submitted) {
        if (submitted.isBlank()) {
            results = emptyMap(); failed = emptyList()
            return@LaunchedEffect
        }
        loading = true
        val (data, errors) = withContext(Dispatchers.IO) {
            val list = listOf(
                LibrarySection.MUSIC, LibrarySection.MEDIA,
                LibrarySection.RADIO, LibrarySection.TV
            )
            val catalog = TromuwelleCatalog(session)
            val found = mutableMapOf<LibrarySection, List<LibraryEntry>>()
            val failedSections = mutableListOf<String>()
            list.forEach { section ->
                try {
                    found[section] = catalog.load(section, search = submitted).items.take(16)
                } catch (_: Exception) {
                    failedSections += section.label
                }
            }
            found to failedSections
        }
        results = data; failed = errors; loading = false
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Rounded.ArrowBack, contentDescription = "Zurück")
            }
            Spacer(Modifier.width(7.dp))
            Text("Alles durchsuchen", style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold)
        }
        OutlinedTextField(
            value = query, onValueChange = { query = it.take(120) },
            label = { Text("Musik, Videos, Radio, Fernsehen") },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            trailingIcon = { IconButton(onClick = { submitted = query.trim() }) {
                Icon(Icons.Rounded.ArrowForward, contentDescription = "Suchen")
            } },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { submitted = query.trim() }),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            shape = RoundedCornerShape(20.dp)
        )
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
        if (submitted.isBlank()) {
            Text("Wonach suchst du?", Modifier.padding(start = 18.dp, top = 26.dp, bottom = 6.dp),
                fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
            Text("Durchsuche deine eigenen Tromuwelle-Medien. " +
                "Die private 18+-Bibliothek bleibt bewusst außerhalb der globalen Suche.",
                Modifier.padding(horizontal = 18.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(LibrarySection.MUSIC, LibrarySection.MEDIA, LibrarySection.RADIO).forEach { section ->
                    AssistChip(onClick = { onSection(section) }, label = { Text(section.label) })
                }
            }
        } else {
            val count = results.values.sumOf { it.size }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 30.dp)) {
                if (failed.isNotEmpty() && !loading) item {
                    Text("Nicht erreichbar: " + failed.joinToString(", "),
                        Modifier.padding(18.dp), color = MaterialTheme.colorScheme.error)
                }
                if (!loading && count == 0) item {
                    Text("Keine passenden Inhalte gefunden.",
                        Modifier.padding(24.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                results.forEach { (section, entries) ->
                    if (entries.isNotEmpty()) {
                        item {
                            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 10.dp, top = 18.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Text(section.label, modifier = Modifier.weight(1f),
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.titleLarge)
                                TextButton(onClick = { onSection(section) }) {
                                    Text("Öffnen")
                                    Icon(Icons.Rounded.ChevronRight, null)
                                }
                            }
                        }
                        items(entries, key = { section.name + ":" + it.kind.name + ":" + it.id }) { entry ->
                            StreamTrackRow(session, entry, favorite = false,
                                onOpen = { onOpen(section, entry) }, onFavorite = {},
                                onInfo = null)
                        }
                    }
                }
            }
        }
    }
}
