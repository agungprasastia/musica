package com.music.musica.data.stream.potoken

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString

internal class BotGuardException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
internal class UnusableWebViewException(message: String) : RuntimeException(message)

/**
 * Encoding/decoding helpers for YouTube's BotGuard `Create` and `GenerateIT` payloads.
 */
internal object BotGuardCodec {

    fun parseChallengeData(rawChallengeData: String): String {
        val scrambled = Json.parseToJsonElement(rawChallengeData).jsonArray
        val challengeData = if (scrambled.size > 1 && scrambled[1].jsonPrimitive.isString) {
            val descrambled = descramble(scrambled[1].jsonPrimitive.content)
            Json.parseToJsonElement(descrambled).jsonArray
        } else {
            scrambled[0].jsonArray
        }

        val messageId = challengeData[0].jsonPrimitive.content
        val interpreterHash = challengeData[3].jsonPrimitive.content
        val program = challengeData[4].jsonPrimitive.content
        val globalName = challengeData[5].jsonPrimitive.content
        val clientExperimentsStateBlob = challengeData.getOrNull(7)
            ?.takeIf { it !is JsonNull }
            ?.jsonPrimitive?.content.orEmpty()

        val safeScript = challengeData[1]
            .takeIf { it !is JsonNull }
            ?.jsonArray
            ?.find { it.jsonPrimitive.isString }
        val trustedUrl = challengeData[2]
            .takeIf { it !is JsonNull }
            ?.jsonArray
            ?.find { it.jsonPrimitive.isString }

        return Json.encodeToString(
            JsonObject.serializer(),
            JsonObject(
                mapOf(
                    "messageId" to JsonPrimitive(messageId),
                    "interpreterJavascript" to JsonObject(
                        mapOf(
                            "privateDoNotAccessOrElseSafeScriptWrappedValue" to (safeScript ?: JsonNull),
                            "privateDoNotAccessOrElseTrustedResourceUrlWrappedValue" to (trustedUrl ?: JsonNull),
                        ),
                    ),
                    "interpreterHash" to JsonPrimitive(interpreterHash),
                    "program" to JsonPrimitive(program),
                    "globalName" to JsonPrimitive(globalName),
                    "clientExperimentsStateBlob" to JsonPrimitive(clientExperimentsStateBlob),
                ),
            ),
        )
    }

    fun parseIntegrityTokenData(rawIntegrityTokenData: String): Pair<String, Long> {
        val array = Json.parseToJsonElement(rawIntegrityTokenData).jsonArray
        val tokenU8 = base64ToJsUint8Array(array[0].jsonPrimitive.content)
        val expiresInSec = array[1].jsonPrimitive.long
        return tokenU8 to expiresInSec
    }

    fun stringToJsUint8Array(identifier: String): String =
        bytesToJsUint8Array(identifier.toByteArray(Charsets.UTF_8))

    fun u8CsvToUrlSafeBase64(csvBytes: String): String =
        csvBytes.split(',')
            .map { it.trim().toUByte().toByte() }
            .toByteArray()
            .toByteString()
            .base64()
            .replace('+', '-')
            .replace('/', '_')

    private fun descramble(scrambled: String): String =
        decodeUrlSafeBase64(scrambled)
            .map { (it + 97).toByte() }
            .toByteArray()
            .decodeToString()

    private fun base64ToJsUint8Array(base64: String): String =
        bytesToJsUint8Array(decodeUrlSafeBase64(base64))

    private fun bytesToJsUint8Array(bytes: ByteArray): String =
        "new Uint8Array([${bytes.joinToString(",") { it.toUByte().toString() }}])"

    private fun decodeUrlSafeBase64(base64: String): ByteArray {
        val normalized = base64
            .replace('-', '+')
            .replace('_', '/')
            .replace('.', '=')
        return (normalized.decodeBase64() ?: throw BotGuardException("Failed to base64 decode payload"))
            .toByteArray()
    }
}
