package de.tromuwelle.android

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Versioned karaoke assets are always resolved through the authenticated server. */
data class KaraokeVersion(val id: String, val profile: String, val pinned: Boolean = false)
data class KaraokeSong(
    val id: String, val title: String, val artist: String, val album: String,
    val duration: Double, val versions: List<KaraokeVersion> = emptyList(),
    val ready: List<String> = emptyList()
) {
    val playable get() = versions.isNotEmpty() || ready.isNotEmpty()
}

data class KaraokeWord(val text: String, val start: Double, val end: Double)
data class KaraokeLine(val text: String, val start: Double, val end: Double,
                       val words: List<KaraokeWord> = emptyList())
data class KaraokeFrame(
    val current: KaraokeLine?, val previous: KaraokeLine?, val next: KaraokeLine?,
    val countdown: Int? = null, val outro: Boolean = false
)

fun karaokeSongs(data: JSONObject): List<KaraokeSong> =
    (data.optJSONArray("items") ?: JSONArray()).objectsKaraoke().map { item ->
        KaraokeSong(
            item.optString("id"), item.optString("title"),
            item.optString("artist"), item.optString("album"),
            item.optDouble("duration").takeIf { it.isFinite() } ?: 0.0,
            karaokeVersions(item),
            (item.optJSONArray("ready") ?: JSONArray()).stringsKaraoke()
        )
    }

fun karaokeVersions(data: JSONObject): List<KaraokeVersion> =
    (data.optJSONArray("versions") ?: JSONArray()).objectsKaraoke().map {
        KaraokeVersion(it.optString("id"), it.optString("profile"),
            it.optBoolean("pinned"))
    }.filter { it.id.isNotBlank() && it.profile in listOf("2", "6") }

fun karaokeArtifactVersions(data: JSONObject): List<KaraokeVersion> =
    (data.optJSONArray("items") ?: JSONArray()).objectsKaraoke().map {
        KaraokeVersion(it.optString("id"), it.optString("profile"),
            it.optBoolean("pinned"))
    }.filter { it.id.isNotBlank() && it.profile in listOf("2", "6") }

/** Same estimated LRC end times and readable lyric hold logic as the web stage. */
fun karaokeLyrics(response: JSONObject): List<KaraokeLine> {
    val stored = response.optJSONObject("payload")?.optJSONArray("lines")
    if (stored != null) {
        return stored.objectsKaraoke().mapNotNull { line ->
            val start = line.optDouble("start", Double.NaN)
            val end = line.optDouble("end", Double.NaN)
            if (!start.isFinite() || !end.isFinite() || start < 0 || end < start) null
            else KaraokeLine(line.optString("text"), start, end,
                (line.optJSONArray("words") ?: JSONArray()).objectsKaraoke().mapNotNull { word ->
                    val ws = word.optDouble("start", Double.NaN)
                    val we = word.optDouble("end", Double.NaN)
                    if (ws.isFinite() && we.isFinite() && ws >= 0 && we >= ws)
                        KaraokeWord(word.optString("text"), ws, we) else null
                })
        }.sortedBy { it.start }
    }
    val lrc = response.optJSONObject("source")?.optString("synced").orEmpty()
    val pattern = Regex("""\[(\d{1,3}):(\d{1,2}(?:\.\d+)?)\]""")
    val starts = lrc.lineSequence().flatMap { line ->
        val match = pattern.findAll(line).toList()
        val words = line.substringAfterLast("]").trim()
        match.asSequence().mapNotNull { token ->
            val at = (token.groupValues[1].toIntOrNull() ?: return@mapNotNull null) * 60.0 +
                (token.groupValues[2].toDoubleOrNull() ?: return@mapNotNull null)
            if (words.isBlank()) null else at to words
        }
    }.sortedBy { it.first }.toList()
    return starts.mapIndexed { index, (start, text) ->
        val estimated = (text.split(Regex("""\s+""")).size * 0.5).coerceIn(1.5, 7.0)
        val next = starts.getOrNull(index + 1)?.first ?: start + 7.0
        KaraokeLine(text, start, max(start, min(start + estimated, next - 0.1)))
    }
}

/** Never clear a readable phrase until the next line starts for short pauses. */
fun karaokeFrame(lines: List<KaraokeLine>, second: Double): KaraokeFrame {
    val t = second.coerceAtLeast(0.0)
    val index = lines.indexOfLast { it.start <= t }
    if (index < 0) return KaraokeFrame(null, null, lines.firstOrNull())
    val line = lines[index]
    val next = lines.getOrNull(index + 1)
    val gap = if (next != null) next.start - max(line.start, line.end) else 0.0
    val hold = if (next == null) line.end + 12.0
        else if (gap < 10.0) next.start else min(next.start, line.end + 4.0)
    val current = line.takeIf { t < hold }
    val countdown = if (current == null && next != null && gap >= 10 && t < next.start)
        ceil(next.start - t).toInt().coerceAtLeast(0) else null
    return KaraokeFrame(current, if (current == null) line else lines.getOrNull(index - 1),
        next, countdown, next == null && current == null)
}

fun JSONArray.objectsKaraoke(): List<JSONObject> =
    (0 until length()).mapNotNull { optJSONObject(it) }
fun JSONArray.stringsKaraoke(): List<String> =
    (0 until length()).map { optString(it) }.filter { it.isNotBlank() }

class KaraokeApi(private val session: TromuwelleSession) {
    private val base = "/api/karaoke"
    private fun enc(id: String) = session.query(id)
    fun status() = JSONObject(session.get("$base/status"))
    fun ready(q: String = "", profile: String = "all", sort: String = "recent", offset: Int = 0) =
        JSONObject(session.get("$base/ready?q=${enc(q)}&profile=${enc(profile)}&sort=${enc(sort)}&limit=24&offset=$offset"))
    fun tracks(q: String = "", offset: Int = 0) =
        JSONObject(session.get("$base/tracks?q=${enc(q)}&offset=$offset&limit=24"))
    fun versions(id: String) = karaokeArtifactVersions(JSONObject(session.get("$base/tracks/${enc(id)}/versions")))
    fun lyrics(id: String) = karaokeLyrics(JSONObject(session.get("$base/lyrics/${enc(id)}")))
    fun job(id: String, profile: String) = JSONObject(session.post("$base/jobs",
        JSONObject().put("track_id", id).put("profile", profile).put("kind", "separate").toString()))
    fun jobs() = JSONObject(session.get("$base/jobs"))
    fun cancelJob(id: String) = session.delete("$base/jobs/${enc(id)}")
    fun playback(id: String, lease: String) = session.post("$base/artifacts/${enc(id)}/playback",
        JSONObject().put("lease_id", lease).toString())
    fun release(id: String, lease: String) = session.delete("$base/artifacts/${enc(id)}/playback/${enc(lease)}")
    fun stemUrl(id: String, name: String) = session.url("$base/artifacts/${enc(id)}/${enc(name)}")
    fun sessions() = JSONObject(session.get("$base/sessions")).optJSONArray("items") ?: JSONArray()
    fun createParty(title: String) = JSONObject(session.post("$base/sessions",
        JSONObject().put("title", title).toString()))
    fun party(id: String) = JSONObject(session.get("$base/sessions/${enc(id)}"))
    fun addParty(id: String, song: KaraokeSong, artifact: KaraokeVersion, singer: String) =
        JSONObject(session.post("$base/sessions/${enc(id)}/requests",
            JSONObject().put("track_id", song.id).put("artifact_id", artifact.id)
                .put("singer", singer).toString()))
    fun controlParty(id: String, body: JSONObject) = session.post("$base/sessions/${enc(id)}/control", body.toString())
    fun setlistAction(id: String, item: String, operation: String) =
        session.post("$base/sessions/${enc(id)}/setlist/${enc(item)}",
            JSONObject().put("operation", operation).toString())
    fun invite(id: String) = JSONObject(session.post("$base/sessions/${enc(id)}/invite"))
    fun closeParty(id: String) = session.post("$base/sessions/${enc(id)}/close")
}
