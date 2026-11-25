package com.music.musica.core.auth

import java.security.MessageDigest

/**
 * Signs authenticated YouTube Music (InnerTube) requests using Google's SAPISIDHASH scheme.
 */
object SapisidHashSigner {

    private val SAPISID_KEYS = listOf("SAPISID", "__Secure-3PAPISID", "__Secure-1PAPISID")

    fun extractSapisid(cookieHeader: String?): String? {
        if (cookieHeader.isNullOrBlank()) return null
        val cookieMap = buildMap {
            cookieHeader.split(';').forEach { part ->
                val idx = part.indexOf('=')
                if (idx > 0) {
                    val key = part.substring(0, idx).trim()
                    val value = part.substring(idx + 1).trim()
                    if (key.isNotEmpty() && value.isNotEmpty()) {
                        put(key, value)
                    }
                }
            }
        }
        return SAPISID_KEYS.firstNotNullOfOrNull { cookieMap[it] }
    }

    fun sign(
        sapisid: String,
        origin: String = "https://music.youtube.com",
        epochSeconds: Long = System.currentTimeMillis() / 1000L,
    ): String {
        val payload = "$epochSeconds $sapisid $origin"
        val digest = MessageDigest.getInstance("SHA-1").digest(payload.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }
        return "SAPISIDHASH ${epochSeconds}_$hex"
    }

    fun authorizationHeader(
        cookieHeader: String?,
        origin: String = "https://music.youtube.com",
        epochSeconds: Long = System.currentTimeMillis() / 1000L,
    ): String? {
        val sapisid = extractSapisid(cookieHeader) ?: return null
        return sign(sapisid = sapisid, origin = origin, epochSeconds = epochSeconds)
    }
}
