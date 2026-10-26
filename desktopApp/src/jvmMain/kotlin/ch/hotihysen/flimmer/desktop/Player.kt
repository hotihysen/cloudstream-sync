package ch.hotihysen.flimmer.desktop

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.flimmer.FlimmerStreams
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Wiedergabe mit mpv (liegt im Ordner «mpv» neben Flimmer.exe).
 * Ein kleines Lua-Skript schreibt alle paar Sekunden die Position in eine Datei; daraus entstehen
 * «Weiterschauen», der Gist-Sync und das Scrobbeln bei Trakt.
 */
object Player {
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

    data class Result(val positionMs: Long, val durationMs: Long, val link: ExtractorLink?, val error: String?)

    fun mpvPath(): String? {
        val exe = if (System.getProperty("os.name").lowercase().contains("win")) "mpv.exe" else "mpv"
        Store.appDir?.let { dir ->
            listOf(File(dir, "mpv/$exe"), File(dir, "app/mpv/$exe"), File(dir, exe)).firstOrNull { it.exists() }?.let { return it.absolutePath }
        }
        Store.getString("player/mpv")?.takeIf { File(it).exists() }?.let { return it }
        // mpv aus dem Systempfad
        System.getenv("PATH")?.split(File.pathSeparator)?.forEach { p ->
            val f = File(p, exe)
            if (f.exists()) return f.absolutePath
        }
        return null
    }

    private val script: File by lazy {
        File(Store.cacheDir, "flimmer-position.lua").apply {
            writeText(
                """
                local opts = { posfile = "" }
                require("mp.options").read_options(opts, "flimmer")
                local function save()
                  local pos = mp.get_property_number("time-pos")
                  local dur = mp.get_property_number("duration")
                  if pos and dur and opts.posfile ~= "" then
                    local f = io.open(opts.posfile, "w")
                    if f then
                      f:write(string.format("%d %d", math.floor(pos * 1000), math.floor(dur * 1000)))
                      f:close()
                    end
                  end
                end
                mp.add_periodic_timer(3, save)
                mp.register_event("seek", save)
                mp.register_event("end-file", save)
                mp.add_hook("on_unload", 50, save)
                """.trimIndent()
            )
        }
    }

    /**
     * Spielt [links] der Reihe nach ab: bricht ein Stream sofort ab, kommt automatisch der nächste.
     * @param onStarted wird aufgerufen, sobald ein Stream läuft (für Trakt)
     */
    suspend fun play(
        title: String,
        links: List<ExtractorLink>,
        subtitles: List<SubtitleFile>,
        startMs: Long,
        onStarted: (ExtractorLink) -> Unit = {},
    ): Result = withContext(Dispatchers.IO) {
        val mpv = mpvPath() ?: return@withContext Result(0, 0, null, "Videoplayer (mpv) nicht gefunden")
        var lastError: String? = null
        for ((index, link) in links.withIndex().take(4)) {
            val posFile = File(Store.cacheDir, "position.txt").apply { delete() }
            val args = mutableListOf(
                mpv,
                "--force-window=immediate",
                "--keep-open=no",
                "--title=$title · Flimmer",
                "--user-agent=${link.headers["User-Agent"] ?: USER_AGENT}",
                "--alang=de,ger,deu,en,eng",
                "--slang=de,ger,deu",
                "--script=${script.absolutePath}",
                "--script-opts=flimmer-posfile=${posFile.absolutePath}",
                "--network-timeout=20",
            )
            if (Store.getBoolean("player/fullscreen", true)) args += "--fs"
            if (link.referer.isNotBlank()) args += "--referrer=${link.referer}"
            link.headers.filterKeys { !it.equals("User-Agent", true) && !it.equals("Referer", true) }
                .forEach { (k, v) -> args += "--http-header-fields-append=$k: $v" }
            if (startMs > 5_000) args += "--start=${startMs / 1000}"
            subtitles.take(8).forEach { args += "--sub-files-append=${it.url}" }
            args += link.url

            val log = File(Store.cacheDir, "mpv.log")
            val started = System.currentTimeMillis()
            val process = ProcessBuilder(args).redirectErrorStream(true).redirectOutput(log).start()
            onStarted(link)
            val exit = process.waitFor()
            val ran = System.currentTimeMillis() - started
            val (pos, dur) = readPos(posFile)

            val failed = pos < 2_000 && ran < 15_000 && exit != 0
            if (failed) {
                FlimmerStreams.record(link, false)
                lastError = log.takeIf { it.exists() }?.readLines()?.lastOrNull { it.contains("rror") || it.contains("ailed") }
                    ?: "Stream startet nicht"
                if (index < links.lastIndex) continue
                return@withContext Result(0, 0, link, lastError)
            }
            if (pos > 20_000) FlimmerStreams.record(link, true)
            return@withContext Result(pos, dur, link, null)
        }
        Result(0, 0, null, lastError ?: "Keine Quelle abspielbar")
    }

    private fun readPos(f: File): Pair<Long, Long> = try {
        val parts = f.readText().trim().split(" ")
        (parts[0].toLongOrNull() ?: 0L) to (parts.getOrNull(1)?.toLongOrNull() ?: 0L)
    } catch (_: Throwable) {
        0L to 0L
    }
}
