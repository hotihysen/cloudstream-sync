package com.lagradost.cloudstream3.flimmer

import ch.hotihysen.flimmer.desktop.Store
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * Stream-Bewertung unter Windows (gleiche Regeln wie in der Android-App):
 * Sprache zuerst, dann Zuverlässigkeit des Hosters, dann Qualität.
 */
object FlimmerStreams {
    const val LANG_DE = "de"
    const val LANG_ORIGINAL = "original"
    const val LANG_ANY = "any"

    var language: String
        get() = Store.getString("streams/language") ?: LANG_DE
        set(value) = Store.putString("streams/language", value)

    fun languageLabel(lang: String = language) = when (lang) {
        LANG_DE -> "Deutsch bevorzugen"
        LANG_ORIGINAL -> "Original mit Untertiteln bevorzugen"
        else -> "Keine Bevorzugung"
    }

    private data class Origin(val provider: String, val providerLang: String?)

    private val origins = ConcurrentHashMap<String, Origin>()

    fun register(link: ExtractorLink, provider: String, providerLang: String?) {
        origins[link.url] = Origin(provider, providerLang)
    }

    fun providerOf(link: ExtractorLink?): String? = link?.let { origins[it.url]?.provider }

    private val germanMarkers = Regex("""(?i)(\bgerman\b|\bdeutsch|\bger[ ._-]?dub\b|\bgerdub\b|\bdub[ ._-]?ger\b|\[de\]|\(de\)|🇩🇪)""")
    private val subMarkers = Regex("""(?i)(\bomu\b|\bger[ ._-]?sub\b|\bgersub\b|\beng[ ._-]?sub\b|\bsub(bed|s)?\b|untertitel|\bvostfr\b|\bmit ut\b)""")
    private val otherLanguageMarkers = Regex("""(?i)(\benglisch|\benglish\b|\beng\b|\bjapanisch|\bjapanese\b|\bjap\b|\boriginal\b|\bfranz|\bfrench\b|\bspanisch|\bitalienisch|🇬🇧|🇺🇸|🇯🇵)""")

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
                markedGerman && !markedSub -> 3
                markedSub && (markedGerman || germanProvider) && !markedOther -> 1
                markedOther -> 0
                unmarked && germanProvider -> 2
                else -> 0
            }
            LANG_ORIGINAL -> when {
                markedOther || markedSub -> 2
                markedGerman -> 0
                unmarked && !germanProvider -> 1
                else -> 0
            }
            else -> 0
        }
    }

    // ------------------------------------------------------------------ Zuverlässigkeit

    private fun statsKey(provider: String, source: String) = "streams/stats/$provider|$source"

    private fun stats(provider: String, source: String): Pair<Int, Int> {
        val parts = (Store.getString(statsKey(provider, source)) ?: return 0 to 0).split("|")
        return (parts.getOrNull(0)?.toIntOrNull() ?: 0) to (parts.getOrNull(1)?.toIntOrNull() ?: 0)
    }

    fun record(link: ExtractorLink?, success: Boolean) {
        link ?: return
        val provider = providerOf(link) ?: return
        val (ok, fail) = stats(provider, link.source)
        val newOk = (if (success) ok + 1 else ok).coerceAtMost(20)
        val newFail = (if (success) fail else fail + 1).coerceAtMost(20)
        Store.putString(statsKey(provider, link.source), "$newOk|$newFail")
    }

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

    fun bonus(link: ExtractorLink?): Int {
        link ?: return 0
        val origin = origins[link.url] ?: return 0
        return try {
            languageScore(link, origin) + reliabilityScore(link, origin)
        } catch (t: Throwable) {
            0
        }
    }

    /** Sortierschlüssel: Bonus zählt mehr als Qualität. */
    fun rank(link: ExtractorLink): Int {
        val q = if (link.quality <= 0 || link.quality == Qualities.Unknown.value) 480 else link.quality
        return bonus(link) * 10_000 + q.coerceAtMost(4320)
    }

    fun isExcellent(link: ExtractorLink): Boolean {
        val q = link.quality
        val goodQuality = q >= Qualities.P1080.value && q != Qualities.Unknown.value
        return goodQuality && bonus(link) >= 3
    }

    // ------------------------------------------------------------------ Früh starten

    @Volatile var startNowListener: (() -> Unit)? = null
    @Volatile var lastReport: String? = null
        set(value) {
            field = value
            reportListener?.invoke(value)
        }
    @Volatile var reportListener: ((String?) -> Unit)? = null

    fun requestStartNow() {
        try {
            startNowListener?.invoke()
        } catch (_: Throwable) {
        }
    }

    // ------------------------------------------------------------------ Qualität ermitteln

    private val resolutionRegex = Regex("""RESOLUTION=\d+x(\d+)""")

    suspend fun probeQuality(link: ExtractorLink) {
        val unknown = link.quality <= 0 || link.quality == Qualities.Unknown.value
        if (!unknown || link.type != ExtractorLinkType.M3U8) return
        try {
            val text = withTimeoutOrNull(4_000L) {
                val headers = link.headers + if (link.referer.isNotBlank()) mapOf("Referer" to link.referer) else emptyMap()
                val res = app.get(link.url, headers = headers)
                if (res.isSuccessful) res.text.take(64_000) else null
            } ?: return
            val best = resolutionRegex.findAll(text).mapNotNull { it.groupValues[1].toIntOrNull() }.maxOrNull() ?: return
            link.quality = Qualities.entries
                .filter { it != Qualities.Unknown }
                .minByOrNull { kotlin.math.abs(it.value - best) }?.value ?: return
        } catch (_: Throwable) {
        }
    }
}
