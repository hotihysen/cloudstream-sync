package com.lagradost.cloudstream3.flimmer

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * Bessere Stream-Auswahl für den Flimmer Katalog.
 *
 * Jeder Link bekommt einen Zuschlag (oder Abzug) auf die übliche Priorität aus Qualität und Quelle:
 * - Sprache: deutschsprachige Erweiterungen bzw. als Deutsch markierte Links zuerst (einstellbar)
 * - Zuverlässigkeit: Flimmer merkt sich, welche Erweiterung/Hoster-Kombination bei dir startet
 *   und welche abbricht
 * Unbekannte Qualität wird bei HLS-Streams aus der Stream-Liste ausgelesen.
 */
object FlimmerStreams {
    private const val TAG = "FlimmerStreams"
    private const val PREFS = "flimmer_streams"
    private const val K_LANG = "language"
    private const val STATS = "stats/"

    const val LANG_DE = "de"
    const val LANG_ORIGINAL = "original"
    const val LANG_ANY = "any"

    private val ctx: Context? get() = CloudStreamApp.context
    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Bevorzugte Sprache: «de», «original» oder «any» */
    var language: String
        get() = ctx?.let { prefs(it).getString(K_LANG, LANG_DE) } ?: LANG_DE
        set(value) {
            ctx?.let { c -> prefs(c).edit { putString(K_LANG, value) } }
        }

    fun languageLabel(lang: String = language) = when (lang) {
        LANG_DE -> "Deutsch bevorzugen"
        LANG_ORIGINAL -> "Original mit Untertiteln bevorzugen"
        else -> "Keine Bevorzugung"
    }

    // ------------------------------------------------------------------ Herkunft der Links

    private data class Origin(val provider: String, val providerLang: String?)

    /** URL → Erweiterung, aus der der Link stammt */
    private val origins = ConcurrentHashMap<String, Origin>()

    fun register(link: ExtractorLink, provider: String, providerLang: String?) {
        origins[link.url] = Origin(provider, providerLang)
    }

    fun providerOf(link: ExtractorLink?): String? = link?.let { origins[it.url]?.provider }

    // ------------------------------------------------------------------ Sprache

    private val germanMarkers = Regex("""(?i)(\bgerman\b|\bdeutsch|\bger[ ._-]?dub\b|\bgerdub\b|\bdub[ ._-]?ger\b|\[de\]|\(de\)|🇩🇪)""")
    private val subMarkers = Regex("""(?i)(\bomu\b|\bger[ ._-]?sub\b|\bgersub\b|\beng[ ._-]?sub\b|\bsub(bed|s)?\b|untertitel|\bvostfr\b|\bmit ut\b)""")
    private val otherLanguageMarkers = Regex("""(?i)(\benglisch|\benglish\b|\beng\b|\bjapanisch|\bjapanese\b|\bjap\b|\boriginal\b|\bfranz|\bfrench\b|\bspanisch|\bitalienisch|🇬🇧|🇺🇸|🇯🇵)""")

    /**
     * Sprachbewertung eines Links.
     * Ausdrückliche Angaben im Namen («[Deutsch]», «[Englisch]», «Ger-Sub» …) haben Vorrang;
     * nur wenn ein Link gar keine Sprache nennt, zählt die Sprache der Erweiterung.
     */
    private fun languageScore(link: ExtractorLink, origin: Origin?): Int {
        val pref = language
        if (pref == LANG_ANY) return 0
        val text = "${link.name} ${link.source}"
        val germanProvider = origin?.providerLang?.lowercase()?.startsWith("de") == true
        val markedGerman = germanMarkers.containsMatchIn(text)
        val markedSub = subMarkers.containsMatchIn(text)
        val markedOther = otherLanguageMarkers.containsMatchIn(text)
        val unmarked = !markedGerman && !markedSub && !markedOther
        return when (pref) {
            LANG_DE -> when {
                markedGerman && !markedSub -> 3          // deutsche Tonspur
                markedSub && (markedGerman || germanProvider) && !markedOther -> 1 // deutsche Untertitel
                markedOther -> 0                         // andere Sprache, auch bei deutscher Erweiterung
                unmarked && germanProvider -> 2          // vermutlich deutsch
                else -> 0
            }
            LANG_ORIGINAL -> when {
                markedOther || markedSub -> 2            // Originalton, ggf. mit Untertiteln
                markedGerman -> 0
                unmarked && !germanProvider -> 1
                else -> 0
            }
            else -> 0
        }
    }

    // ------------------------------------------------------------------ Zuverlässigkeit

    private fun statsKey(provider: String, source: String) = STATS + "$provider|$source"

    private fun stats(provider: String, source: String): Pair<Int, Int> {
        val c = ctx ?: return 0 to 0
        val raw = prefs(c).getString(statsKey(provider, source), null) ?: return 0 to 0
        val parts = raw.split("|")
        return (parts.getOrNull(0)?.toIntOrNull() ?: 0) to (parts.getOrNull(1)?.toIntOrNull() ?: 0)
    }

    private fun record(link: ExtractorLink?, success: Boolean) {
        val c = ctx ?: return
        link ?: return
        val provider = providerOf(link) ?: return
        val (ok, fail) = stats(provider, link.source)
        // Ältere Erfahrungen langsam vergessen, damit sich ein Hoster erholen kann
        val newOk = (if (success) ok + 1 else ok).coerceAtMost(20)
        val newFail = (if (success) fail else fail + 1).coerceAtMost(20)
        prefs(c).edit { putString(statsKey(provider, link.source), "$newOk|$newFail") }
    }

    private val reportedOk = ConcurrentHashMap.newKeySet<String>()

    /** Wiedergabe läuft stabil (mind. 20 Sekunden) */
    fun onPlaying(link: ExtractorLink?) {
        link ?: return
        if (reportedOk.add(link.url)) record(link, true)
    }

    /** Wiedergabe mit diesem Link fehlgeschlagen */
    fun onError(link: ExtractorLink?) = record(link, false)

    private fun reliabilityScore(link: ExtractorLink, origin: Origin?): Int {
        origin ?: return 0
        val (ok, fail) = stats(origin.provider, link.source)
        val total = ok + fail
        if (total < 2) return 0
        val rate = ok.toDouble() / total
        return when {
            rate >= 0.8 -> 1
            rate >= 0.5 -> 0
            rate >= 0.25 -> -2
            else -> -4
        }
    }

    /** Zuschlag auf die Link-Priorität (QualityDataHelper.getLinkPriority) */
    fun bonus(link: ExtractorLink?): Int {
        link ?: return 0
        val origin = origins[link.url] ?: return 0 // nur Links aus dem Katalog
        return try {
            languageScore(link, origin) + reliabilityScore(link, origin)
        } catch (t: Throwable) {
            0
        }
    }

    /** Hohe Gesamtbewertung: lohnt sich nicht, noch lange auf weitere Quellen zu warten */
    fun isExcellent(link: ExtractorLink): Boolean {
        val q = link.quality
        val goodQuality = q >= Qualities.P1080.value && q != Qualities.Unknown.value
        return goodQuality && bonus(link) >= 3
    }

    // ------------------------------------------------------------------ Früh starten, weiterladen

    /**
     * Wird vom Player gesetzt: startet die Wiedergabe mit dem bisher besten Link,
     * während die übrigen Erweiterungen im Hintergrund weiter Quellen liefern.
     */
    @Volatile var startNowListener: (() -> Unit)? = null

    fun requestStartNow() {
        try {
            startNowListener?.invoke()
        } catch (t: Throwable) {
            Log.w(TAG, "startNow", t)
        }
    }

    // ------------------------------------------------------------------ Qualität ermitteln

    private val resolutionRegex = Regex("""RESOLUTION=\d+x(\d+)""")

    /**
     * Liest bei HLS-Streams ohne Qualitätsangabe die höchste Auflösung aus der Stream-Liste.
     * Höchstens 4 Sekunden; bei Fehlern bleibt der Link unverändert.
     */
    suspend fun probeQuality(link: ExtractorLink) {
        val unknown = link.quality <= 0 || link.quality == Qualities.Unknown.value
        if (!unknown || link.type != ExtractorLinkType.M3U8) return
        try {
            val text = withTimeoutOrNull(4_000L) {
                val headers = link.headers + if (link.referer.isNotBlank()) mapOf("Referer" to link.referer) else emptyMap()
                val res = app.get(link.url, headers = headers)
                if (res.isSuccessful) res.text.take(64_000) else null
            } ?: return
            val best = resolutionRegex.findAll(text).mapNotNull { it.groupValues[1].toIntOrNull() }.maxOrNull()
                ?: return
            link.quality = Qualities.entries
                .filter { it != Qualities.Unknown }
                .minByOrNull { kotlin.math.abs(it.value - best) }?.value ?: return
        } catch (t: Throwable) {
            Log.w(TAG, "probe ${link.source}: ${t.message}")
        }
    }
}
