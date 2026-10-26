package com.lagradost.cloudstream3.flimmer

import ch.hotihysen.flimmer.desktop.Trakt

/** Brücke für den gemeinsamen Katalog (Reihe «Meine Trakt-Watchlist»). */
object FlimmerTrakt {
    fun isLoggedIn(): Boolean = Trakt.isLoggedIn
    suspend fun watchlist(limit: Int = 40): List<Pair<Boolean, Int>> = Trakt.watchlist(limit)
}
