package com.jobscout.android.network

import com.jobscout.android.data.ConfigRepository
import com.jobscout.android.model.LLMProviderConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.coroutines.delay
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

interface LlmProvider {
    val providerId: String
    val displayName: String
    fun currentModelName(): String

    suspend fun <T> generateStructured(
        systemPrompt: String,
        userPrompt: String,
        schemaHintJson: String,
        serializer: KSerializer<T>,
        temperature: Double = 0.25
    ): T
}

private val sharedHttpClient = OkHttpClient.Builder()
    .connectTimeout(30, TimeUnit.SECONDS)
    .writeTimeout(30, TimeUnit.SECONDS)
    .readTimeout(180, TimeUnit.SECONDS)
    .pingInterval(15, TimeUnit.SECONDS)
    .retryOnConnectionFailure(true)
    .build()

private val sharedJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

/**
 * Extracts a clean JSON object string from raw LLM output:
 * 1. Strips markdown ```json ... ``` fences if present.
 * 2. Finds the outermost balanced `{ ... }` block (respecting string quotes and escapes),
 *    so trailing commentary or a connection drop after the closing `}` never discards a valid response.
 */
fun extractCleanJsonBlock(raw: String): String {
    var s = raw.trim()
    if (s.startsWith("```")) {
        s = s.removePrefix("```json").removePrefix("```JSON").removePrefix("```").trim()
        if (s.endsWith("```")) s = s.dropLast(3).trim()
    }
    val startIdx = s.indexOf('{')
    if (startIdx < 0) return s

    var depth = 0
    var inString = false
    var escaped = false
    for (i in startIdx until s.length) {
        val ch = s[i]
        if (escaped) {
            escaped = false
            continue
        }
        if (ch == '\\' && inString) {
            escaped = true
            continue
        }
        if (ch == '"') {
            inString = !inString
            continue
        }
        if (!inString) {
            if (ch == '{') {
                depth++
            } else if (ch == '}') {
                depth--
                if (depth == 0) {
                    return s.substring(startIdx, i + 1)
                }
            }
        }
    }
    val lastBrace = s.lastIndexOf('}')
    if (lastBrace > startIdx) {
        return s.substring(startIdx, lastBrace + 1)
    }
    return s.substring(startIdx)
}

/**
 * Executes an HTTP request with automatic retry on pre-response connection failures,
 * and reads SSE streamed chunks continuously so mobile NAT / unstable Wi-Fi sockets never sit idle.
 * If the stream drops *after* a complete JSON object `{ ... }` has already been received,
 * recovers and returns the accumulated text instead of wasting the billed API call.
 */
private suspend fun executeStreamingLlmCall(
    requestBuilder: () -> Request,
    providerLabel: String,
    parseSseDataLine: (String) -> String?,
    parseNonStreamBody: (String) -> String?
): String {
    var lastException: Exception? = null
    for (attempt in 1..2) {
        val accumulated = StringBuilder()
        var receivedAnyChunk = false
        try {
            val req = requestBuilder()
            sharedHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val errTxt = runCatching { resp.body?.string().orEmpty() }.getOrDefault("")
                    throw IllegalStateException("$providerLabel HTTP ${resp.code}: $errTxt")
                }
                val body = resp.body ?: throw IllegalStateException("$providerLabel returned empty body.")
                val contentType = resp.header("Content-Type").orEmpty().lowercase()

                BufferedReader(InputStreamReader(body.byteStream(), Charsets.UTF_8)).use { reader ->
                    val rawLines = StringBuilder()
                    var sawSseData = false
                    while (true) {
                        val line = reader.readLine() ?: break
                        val trimmed = line.trim()
                        if (trimmed.startsWith("data:")) {
                            sawSseData = true
                            val dataPayload = trimmed.removePrefix("data:").trim()
                            if (dataPayload == "[DONE]" || dataPayload.isEmpty()) continue
                            val piece = runCatching { parseSseDataLine(dataPayload) }.getOrNull()
                            if (!piece.isNullOrEmpty()) {
                                accumulated.append(piece)
                                receivedAnyChunk = true
                            }
                        } else if (!sawSseData && !contentType.contains("event-stream")) {
                            rawLines.appendLine(line)
                        }
                    }
                    if (!sawSseData && rawLines.isNotEmpty()) {
                        val fullText = rawLines.toString().trim()
                        val extracted = parseNonStreamBody(fullText)
                        if (!extracted.isNullOrEmpty()) {
                            accumulated.append(extracted)
                            receivedAnyChunk = true
                        }
                    }
                }
            }
            val finalStr = accumulated.toString().trim()
            if (finalStr.isNotEmpty()) return finalStr
            throw IllegalStateException("$providerLabel returned an empty completion.")
        } catch (e: IOException) {
            // Check if we already received a complete balanced JSON object before the tail-end socket drop
            if (receivedAnyChunk) {
                val partial = accumulated.toString().trim()
                val candidate = extractCleanJsonBlock(partial)
                if (candidate.startsWith("{") && candidate.endsWith("}") &&
                    runCatching { sharedJson.parseToJsonElement(candidate) }.isSuccess
                ) {
                    return candidate
                }
                // Generation already started on server; do not re-bill unless nothing usable arrived
                throw IOException("$providerLabel stream interrupted over unstable network (${e.message})", e)
            }
            lastException = e
            if (attempt < 2) {
                delay(1200L * attempt)
            }
        } catch (e: Exception) {
            if (receivedAnyChunk) {
                val partial = accumulated.toString().trim()
                val candidate = extractCleanJsonBlock(partial)
                if (candidate.startsWith("{") && candidate.endsWith("}") &&
                    runCatching { sharedJson.parseToJsonElement(candidate) }.isSuccess
                ) {
                    return candidate
                }
            }
            throw e
        }
    }
    throw lastException ?: IllegalStateException("$providerLabel request failed.")
}

class GeminiProvider(
    private val config: LLMProviderConfig,
    private val secrets: ConfigRepository
) : LlmProvider {
    override val providerId = "gemini"
    override val displayName = "Google Gemini API"
    override fun currentModelName() = config.geminiModel

    override suspend fun <T> generateStructured(
        systemPrompt: String,
        userPrompt: String,
        schemaHintJson: String,
        serializer: KSerializer<T>,
        temperature: Double
    ): T = withContext(Dispatchers.IO) {
        val apiKey = secrets.getApiKey(config.geminiApiKeyEnv)
        require(apiKey.isNotBlank()) {
            "Gemini API key not configured. Add ${config.geminiApiKeyEnv} in Settings."
        }
        val url = "https://generativelanguage.googleapis.com/v1beta/models/${config.geminiModel}:streamGenerateContent?alt=sse&key=$apiKey"
        val augmentedSystem = "$systemPrompt\n\nReturn ONLY valid JSON matching this schema:\n$schemaHintJson"
        val payload = buildJsonObject {
            put("systemInstruction", buildJsonObject {
                put("parts", buildJsonArray {
                    add(buildJsonObject { put("text", augmentedSystem) })
                })
            })
            put("contents", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("parts", buildJsonArray {
                        add(buildJsonObject { put("text", userPrompt) })
                    })
                })
            })
            put("generationConfig", buildJsonObject {
                put("temperature", temperature)
                put("responseMimeType", "application/json")
                val tl = config.geminiThinkingLevel.trim().lowercase()
                if (tl.isNotEmpty() && tl != "default") {
                    put("thinkingConfig", buildJsonObject {
                        put("thinkingLevel", tl)
                    })
                }
            })
        }

        val bodyBytes = payload.toString()
        val rawText = executeStreamingLlmCall(
            requestBuilder = {
                Request.Builder()
                    .url(url)
                    .header("Accept", "text/event-stream")
                    .post(bodyBytes.toRequestBody("application/json".toMediaType()))
                    .build()
            },
            providerLabel = "Gemini",
            parseSseDataLine = { dataJson ->
                val obj = sharedJson.parseToJsonElement(dataJson).jsonObject
                val parts = obj["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
                    ?.get("content")?.jsonObject
                    ?.get("parts")?.jsonArray
                parts?.joinToString("") { part ->
                    part.jsonObject["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
                }
            },
            parseNonStreamBody = { fullBody ->
                val el = sharedJson.parseToJsonElement(fullBody)
                when (el) {
                    is JsonObject -> el["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
                        ?.get("content")?.jsonObject
                        ?.get("parts")?.jsonArray?.firstOrNull()?.jsonObject
                        ?.get("text")?.jsonPrimitive?.contentOrNull
                    is JsonArray -> el.joinToString("") { item ->
                        item.jsonObject["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
                            ?.get("content")?.jsonObject
                            ?.get("parts")?.jsonArray?.joinToString("") { p ->
                                p.jsonObject["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
                            }.orEmpty()
                    }
                    else -> null
                }
            }
        )
        sharedJson.decodeFromString(serializer, extractCleanJsonBlock(rawText))
    }
}

open class OpenAiCompatibleProvider(
    override val providerId: String,
    override val displayName: String,
    private val baseUrl: String,
    private val model: String,
    private val apiKeyEnv: String,
    private val secrets: ConfigRepository,
    private val requireKey: Boolean = true
) : LlmProvider {

    override fun currentModelName() = model

    override suspend fun <T> generateStructured(
        systemPrompt: String,
        userPrompt: String,
        schemaHintJson: String,
        serializer: KSerializer<T>,
        temperature: Double
    ): T = withContext(Dispatchers.IO) {
        val key = secrets.getApiKey(apiKeyEnv)
        if (requireKey) {
            require(key.isNotBlank()) {
                "$displayName key not configured. Add $apiKeyEnv in Settings."
            }
        }
        val url = "${baseUrl.trimEnd('/')}/chat/completions"
        val augmentedSystem = "$systemPrompt\n\nReturn ONLY valid JSON matching this schema (no markdown fences):\n$schemaHintJson"

        val payload = buildJsonObject {
            put("model", model)
            put("stream", true)
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "system")
                    put("content", augmentedSystem)
                })
                add(buildJsonObject {
                    put("role", "user")
                    put("content", userPrompt)
                })
            })
            if ("reasoner" !in model.lowercase()) {
                put("temperature", temperature)
                put("response_format", buildJsonObject { put("type", "json_object") })
            }
        }

        val bodyBytes = payload.toString()
        val rawText = executeStreamingLlmCall(
            requestBuilder = {
                Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer ${key.ifBlank { "ollama-local" }}")
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .post(bodyBytes.toRequestBody("application/json".toMediaType()))
                    .build()
            },
            providerLabel = displayName,
            parseSseDataLine = { dataJson ->
                val obj = sharedJson.parseToJsonElement(dataJson).jsonObject
                val choice = obj["choices"]?.jsonArray?.firstOrNull()?.jsonObject
                val deltaContent = choice?.get("delta")?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
                deltaContent ?: choice?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
            },
            parseNonStreamBody = { fullBody ->
                val root = sharedJson.parseToJsonElement(fullBody).jsonObject
                root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
                    ?.get("message")?.jsonObject
                    ?.get("content")?.jsonPrimitive?.contentOrNull
            }
        )
        sharedJson.decodeFromString(serializer, extractCleanJsonBlock(rawText))
    }
}

class AnthropicProvider(
    private val config: LLMProviderConfig,
    private val secrets: ConfigRepository
) : LlmProvider {
    override val providerId = "anthropic"
    override val displayName = "Anthropic Claude API"
    override fun currentModelName() = config.anthropicModel

    override suspend fun <T> generateStructured(
        systemPrompt: String,
        userPrompt: String,
        schemaHintJson: String,
        serializer: KSerializer<T>,
        temperature: Double
    ): T = withContext(Dispatchers.IO) {
        val key = secrets.getApiKey(config.anthropicApiKeyEnv)
        require(key.isNotBlank()) {
            "Anthropic API key not configured. Add ${config.anthropicApiKeyEnv} in Settings."
        }
        val augmentedSystem = "$systemPrompt\n\nReturn ONLY valid JSON matching this schema (no markdown fences):\n$schemaHintJson"
        val payload = buildJsonObject {
            put("model", config.anthropicModel)
            put("max_tokens", 4096)
            put("temperature", temperature)
            put("stream", true)
            put("system", augmentedSystem)
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", userPrompt)
                })
            })
        }

        val bodyBytes = payload.toString()
        val rawText = executeStreamingLlmCall(
            requestBuilder = {
                Request.Builder()
                    .url("https://api.anthropic.com/v1/messages")
                    .header("x-api-key", key)
                    .header("anthropic-version", "2023-06-01")
                    .header("content-type", "application/json")
                    .header("accept", "text/event-stream")
                    .post(bodyBytes.toRequestBody("application/json".toMediaType()))
                    .build()
            },
            providerLabel = "Anthropic",
            parseSseDataLine = { dataJson ->
                val obj = sharedJson.parseToJsonElement(dataJson).jsonObject
                val type = obj["type"]?.jsonPrimitive?.contentOrNull
                when (type) {
                    "content_block_delta" -> obj["delta"]?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull
                    "content_block_start" -> obj["content_block"]?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull
                    else -> null
                }
            },
            parseNonStreamBody = { fullBody ->
                val root = sharedJson.parseToJsonElement(fullBody).jsonObject
                val contentArr = root["content"] as? JsonArray ?: JsonArray(emptyList())
                contentArr.firstNotNullOfOrNull { el ->
                    (el as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull
                }
            }
        )
        sharedJson.decodeFromString(serializer, extractCleanJsonBlock(rawText))
    }
}

object LlmProviderRegistry {
    data class ProviderMeta(val id: String, val name: String, val defaultKeyEnv: String)

    val builtInProviders = listOf(
        ProviderMeta("deepseek", "DeepSeek API", "DEEPSEEK_API_KEY"),
        ProviderMeta("gemini", "Google Gemini API", "GEMINI_API_KEY"),
        ProviderMeta("openai_compatible", "OpenAI / OpenRouter / Groq / Ollama", "OPENAI_API_KEY"),
        ProviderMeta("anthropic", "Anthropic Claude API", "ANTHROPIC_API_KEY")
    )

    fun create(config: LLMProviderConfig, secrets: ConfigRepository): LlmProvider {
        val active = config.activeProvider.ifBlank { "deepseek" }
        // Check custom presets first so user-added future providers work seamlessly
        val custom = config.customPresets.firstOrNull { it.id == active }
        if (custom != null) {
            return OpenAiCompatibleProvider(
                providerId = custom.id,
                displayName = custom.label,
                baseUrl = custom.baseUrl,
                model = custom.model,
                apiKeyEnv = custom.apiKeyName,
                secrets = secrets,
                requireKey = true
            )
        }
        return when (active) {
            "gemini" -> GeminiProvider(config, secrets)
            "deepseek" -> OpenAiCompatibleProvider(
                providerId = "deepseek",
                displayName = "DeepSeek API",
                baseUrl = config.deepseekBaseUrl,
                model = config.deepseekModel,
                apiKeyEnv = config.deepseekApiKeyEnv,
                secrets = secrets,
                requireKey = true
            )
            "openai_compatible" -> OpenAiCompatibleProvider(
                providerId = "openai_compatible",
                displayName = "OpenAI-Compatible",
                baseUrl = config.openaiBaseUrl,
                model = config.openaiModel,
                apiKeyEnv = config.openaiApiKeyEnv,
                secrets = secrets,
                requireKey = false
            )
            "anthropic" -> AnthropicProvider(config, secrets)
            else -> throw IllegalArgumentException("Unknown LLM provider: $active")
        }
    }
}
