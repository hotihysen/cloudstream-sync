package com.lagradost.cloudstream3.flimmer

import java.util.concurrent.ConcurrentHashMap

/**
 * Zusatzinfos für die Kacheln der Bibliothek (z.B. «Als Nächstes: S2 · F5», «Noch 40 Min.»),
 * vom Sync-Dienst (Trakt) beim Laden der Bibliothek gesetzt. Schlüssel ist die Sync-ID.
 */
object FlimmerLibraryInfo {
    private val lines = ConcurrentHashMap<String, String>()

    fun update(map: Map<String, String>) {
        lines.putAll(map)
    }

    fun line(syncId: String?): String? = syncId?.let { lines[it] }
}
