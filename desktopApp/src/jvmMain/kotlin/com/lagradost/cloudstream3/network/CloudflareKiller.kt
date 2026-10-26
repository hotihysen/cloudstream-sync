package com.lagradost.cloudstream3.network

import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Unter Windows gibt es keinen eingebauten Browser für die Cloudflare-Prüfung.
 * Anfragen gehen unverändert durch; Seiten mit aktiver Prüfung liefern dann keine Treffer.
 */
class CloudflareKiller : Interceptor {
    val savedCookies: MutableMap<String, Map<String, String>> = mutableMapOf()

    fun getCookieHeaders(url: String): Headers = Headers.Builder().build()

    override fun intercept(chain: Interceptor.Chain): Response = chain.proceed(chain.request())
}
