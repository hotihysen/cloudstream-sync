package com.lagradost.cloudstream3.utils

import android.util.Base64
import org.json.JSONArray
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Verschlüsselt die Profil-PIN, bevor die Profil-Liste in den Gist geht.
 * Der Schlüssel kommt aus der Gist-ID, die alle verbundenen Geräte kennen;
 * die PIN steht so nie lesbar im Gist.
 */
object ProfileCrypt {
    private const val PREFIX = "enc1:"

    private fun key(secret: String): SecretKeySpec {
        val hash = MessageDigest.getInstance("SHA-256").digest(("flimmer-pin|$secret").toByteArray())
        return SecretKeySpec(hash, "AES")
    }

    private fun encrypt(plain: String, secret: String): String {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(secret), GCMParameterSpec(128, iv))
        val data = iv + cipher.doFinal(plain.toByteArray())
        return PREFIX + Base64.encodeToString(data, Base64.NO_WRAP)
    }

    private fun decrypt(value: String, secret: String): String? = try {
        val data = Base64.decode(value.removePrefix(PREFIX), Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(secret), GCMParameterSpec(128, data.copyOfRange(0, 12)))
        String(cipher.doFinal(data.copyOfRange(12, data.size)))
    } catch (t: Throwable) {
        null
    }

    /** Profil-Liste (JSON-Array) mit verschlüsselter PIN */
    fun protect(json: String, secret: String): String = transform(json) { pin ->
        if (pin.startsWith(PREFIX)) pin else encrypt(pin, secret)
    }

    /** Profil-Liste mit lesbarer PIN; nicht entschlüsselbare PIN wird entfernt (Profil bleibt ungesperrt) */
    fun reveal(json: String, secret: String): String = transform(json) { pin ->
        if (pin.startsWith(PREFIX)) decrypt(pin, secret) else pin
    }

    private fun transform(json: String, f: (String) -> String?): String = try {
        val arr = JSONArray(json)
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val pin = if (o.has("lockPin") && !o.isNull("lockPin")) o.getString("lockPin") else null
            if (pin != null) {
                val out = f(pin)
                if (out == null) o.remove("lockPin") else o.put("lockPin", out)
            }
        }
        arr.toString()
    } catch (t: Throwable) {
        json
    }
}
