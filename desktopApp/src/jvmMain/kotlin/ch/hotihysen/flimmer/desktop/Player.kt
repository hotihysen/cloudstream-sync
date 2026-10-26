package ch.hotihysen.flimmer.desktop

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Wiedergabe mit mpv (Ordner «mpv» neben Flimmer.exe, mit der Oberfläche uosc).
 *
 * Flimmer schreibt eine Sitzungsdatei mit den Folgen und ihren Quellen; das Skript flimmer.lua
 * spielt daraus ab, wechselt bei Fehlern die Quelle, geht zur nächsten Folge und meldet die Position
 * in einer Statusdatei zurück.
 */
object Player {
    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

    /** Eine Folge (oder ein Film) in der Sitzung. */
    class Entry(
        val title: String,
        val startSec: Long,
        val links: List<ExtractorLink>,
        val subtitles: List<SubtitleFile>,
        val searching: Boolean,
    )

    data class Status(val index: Int, val positionMs: Long, val durationMs: Long)

    fun mpvPath(): String? {
        val exe = if (System.getProperty("os.name").lowercase().contains("win")) "mpv.exe" else "mpv"
        Store.appDir?.let { dir ->
            listOf(File(dir, "mpv/$exe"), File(dir, "app/mpv/$exe"), File(dir, exe)).firstOrNull { it.exists() }
                ?.let { return it.absolutePath }
        }
        Store.getString("player/mpv")?.takeIf { File(it).exists() }?.let { return it }
        System.getenv("PATH")?.split(File.pathSeparator)?.forEach { p ->
            val f = File(p, exe)
            if (f.exists()) return f.absolutePath
        }
        return null
    }

    private val script: File
        get() = File(Store.cacheDir, "flimmer.lua").apply {
            val text = Player::class.java.getResourceAsStream("/flimmer.lua")?.use { it.readBytes().decodeToString() }
                ?: error("Player-Skript fehlt")
            if (!exists() || readText() != text) writeText(text)
        }

    val sessionFile: File get() = File(Store.cacheDir, "sitzung.json")
    val statusFile: File get() = File(Store.cacheDir, "status.txt")

    /** Sitzungsdatei schreiben (erst in eine Hilfsdatei, dann ersetzen, damit mpv nie eine halbe Datei liest). */
    @Synchronized
    fun writeSession(entries: List<Entry>, current: Int) {
        val json = buildJsonObject {
            put("current", current)
            put("episodes", JsonArray(entries.map { e ->
                buildJsonObject {
                    put("title", e.title)
                    put("start", e.startSec)
                    put("searching", e.searching)
                    put("links", JsonArray(e.links.map { l ->
                        buildJsonObject {
                            put("name", l.name)
                            put("url", l.url)
                            put("referer", l.referer)
                            put("quality", qualityText(l.quality))
                            l.headers["User-Agent"]?.let { put("ua", it) }
                            put("headers", JsonObject(l.headers.mapValues { JsonPrimitive(it.value) }))
                        }
                    }))
                    put("subs", JsonArray(e.subtitles.take(10).map { s ->
                        buildJsonObject {
                            put("url", s.url)
                            put("lang", s.lang)
                        }
                    }))
                }
            }))
        }
        val tmp = File(Store.cacheDir, "sitzung.tmp")
        tmp.writeText(json.toString())
        Files.move(tmp.toPath(), sessionFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    fun readStatus(): Status? = try {
        val p = statusFile.readText().trim().split(" ")
        Status(p[0].toInt(), p[1].toLong(), p[2].toLong())
    } catch (_: Throwable) {
        null
    }

    /** mpv starten; die Sitzungsdatei muss bereits geschrieben sein. */
    fun launch(): Process {
        val mpv = mpvPath() ?: throw IllegalStateException("Videoplayer (mpv) nicht gefunden")
        statusFile.delete()
        val args = mutableListOf(
            mpv,
            "--idle=yes",
            "--force-window=immediate",
            "--keep-open=yes",
            "--user-agent=$USER_AGENT",
            "--alang=de,ger,deu,en,eng",
            "--slang=de,ger,deu",
            "--network-timeout=20",
            "--script=${script.absolutePath}",
            "--script-opts-append=flimmer-session=${sessionFile.absolutePath}",
            "--script-opts-append=flimmer-status=${statusFile.absolutePath}",
        )
        if (Store.getBoolean("player/fullscreen", true)) args += "--fs"
        val log = File(Store.cacheDir, "mpv.log")
        return ProcessBuilder(args).redirectErrorStream(true).redirectOutput(log).start()
    }
}
