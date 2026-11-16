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
    .readTimeout(120, TimeUnit.SECONDS)
    .build()

private val sharedJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

private fun stripMarkdownFences(raw: String): String {
    var s = raw.trim()
    if (s.startsWith("```")) {
        s = s.removePrefix("```json").removePrefix("```").trim()
        if (s.endsWith("```")) s = s.dropLast(3).trim()
    }
    return s
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
        val url = "https://generativelanguage.googleapis.com/v1beta/models/${config.geminiModel}:generateContent?key=$apiKey"
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

        val req = Request.Builder()
            .url(url)
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val respBody = sharedHttpClient.newCall(req).execute().use { resp ->
            val txt = resp.body?.string() ?: "{}"
            if (!resp.isSuccessful) throw IllegalStateException("Gemini HTTP ${resp.code}: $txt")
            txt
        }
        val root = sharedJson.parseToJsonElement(respBody).jsonObject
        val text = root["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("content")?.jsonObject
            ?.get("parts")?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("text")?.jsonPrimitive?.contentOrNull
            ?: throw IllegalStateException("Gemini returned empty response.")
        sharedJson.decodeFromString(serializer, stripMarkdownFences(text))
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

        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${key.ifBlank { "ollama-local" }}")
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val respBody = sharedHttpClient.newCall(req).execute().use { resp ->
            val txt = resp.body?.string() ?: "{}"
            if (!resp.isSuccessful) throw IllegalStateException("$displayName HTTP ${resp.code}: $txt")
            txt
        }
        val root = sharedJson.parseToJsonElement(respBody).jsonObject
        val rawContent = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("message")?.jsonObject
            ?.get("content")?.jsonPrimitive?.contentOrNull
            ?: throw IllegalStateException("$displayName returned empty response.")
        sharedJson.decodeFromString(serializer, stripMarkdownFences(rawContent))
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
            put("system", augmentedSystem)
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", userPrompt)
                })
            })
        }

        val req = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .header("x-api-key", key)
            .header("anthropic-version", "2023-06-01")
            .header("content-type", "application/json")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val respBody = sharedHttpClient.newCall(req).execute().use { resp ->
            val txt = resp.body?.string() ?: "{}"
            if (!resp.isSuccessful) throw IllegalStateException("Anthropic HTTP ${resp.code}: $txt")
            txt
        }
        val root = sharedJson.parseToJsonElement(respBody).jsonObject
        val contentArr = root["content"] as? JsonArray ?: JsonArray(emptyList())
        val text = contentArr.firstNotNullOfOrNull { el ->
            (el as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull
        } ?: throw IllegalStateException("Anthropic returned empty response.")
        sharedJson.decodeFromString(serializer, stripMarkdownFences(text))
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
