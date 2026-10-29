package ch.hotihysen.flimmer.desktop

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** PIN im Gist verschlüsseln, genau wie die Android-App (AES-GCM, Schlüssel aus der Gist-ID). */
object ProfileCrypt {
    private const val PREFIX = "enc1:"

    private fun key(secret: String) =
        SecretKeySpec(MessageDigest.getInstance("SHA-256").digest("flimmer-pin|$secret".toByteArray()), "AES")

    private fun encrypt(plain: String, secret: String): String {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(secret), GCMParameterSpec(128, iv))
        return PREFIX + Base64.getEncoder().encodeToString(iv + cipher.doFinal(plain.toByteArray()))
    }

    private fun decrypt(value: String, secret: String): String? = try {
        val data = Base64.getDecoder().decode(value.removePrefix(PREFIX))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(secret), GCMParameterSpec(128, data.copyOfRange(0, 12)))
        String(cipher.doFinal(data.copyOfRange(12, data.size)))
    } catch (t: Throwable) {
        null
    }

    private fun transform(json: String, f: (String) -> String?): String {
        val arr = Json.parseToJsonElement(json) as JsonArray
        return JsonArray(arr.map { el ->
            val o = el as? JsonObject ?: return@map el
            val pin = (o["lockPin"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull ?: return@map o
            val out = f(pin) ?: throw IllegalStateException("PIN")
            JsonObject(o + ("lockPin" to JsonPrimitive(out)))
        }).toString()
    }

    fun protect(json: String, secret: String): String = try {
        transform(json) { pin -> if (pin.startsWith(PREFIX)) pin else encrypt(pin, secret) }
    } catch (t: Throwable) {
        json
    }

    /** null, wenn eine PIN nicht entschlüsselt werden kann (dann nichts übernehmen) */
    fun reveal(json: String, secret: String): String? = try {
        transform(json) { pin -> if (pin.startsWith(PREFIX)) decrypt(pin, secret) else pin }
    } catch (t: Throwable) {
        null
    }
}
