package com.jobscout.android.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.jobscout.android.model.CandidateProfile
import com.jobscout.android.model.QABankEntry
import com.jobscout.android.model.ScannerConfig
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

class ConfigRepository(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    private val configFile = File(context.filesDir, "scanner_config.json")
    private val profileFile = File(context.filesDir, "candidate_profile.json")

    private val secretsPrefs: SharedPreferences by lazy {
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                "job_scout_secrets",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (_: Exception) {
            context.getSharedPreferences("job_scout_secrets_fallback", Context.MODE_PRIVATE)
        }
    }

    fun loadConfig(): ScannerConfig {
        if (!configFile.exists()) {
            val assetText = context.assets.open("scanner_config.json").bufferedReader().use { it.readText() }
            // Normalize any legacy model names on first seed while keeping all queries & guardrails
            val parsed = json.decodeFromString<ScannerConfig>(assetText)
            val normalizedLlm = parsed.llm.copy(
                geminiModel = if (parsed.llm.geminiModel == "gemini-3.8-flash") "gemini-2.5-flash" else parsed.llm.geminiModel,
                deepseekModel = if (parsed.llm.deepseekModel == "deepseek-flash") "deepseek-chat" else parsed.llm.deepseekModel
            )
            val seeded = parsed.copy(llm = normalizedLlm)
            saveConfig(seeded)
            return seeded
        }
        return json.decodeFromString(configFile.readText())
    }

    fun saveConfig(config: ScannerConfig): ScannerConfig {
        configFile.writeText(json.encodeToString(config))
        return config
    }

    fun loadProfile(): CandidateProfile {
        if (!profileFile.exists()) {
            val assetText = context.assets.open("candidate_profile.json").bufferedReader().use { it.readText() }
            profileFile.writeText(assetText)
        }
        return json.decodeFromString(profileFile.readText())
    }

    fun saveProfile(profile: CandidateProfile): CandidateProfile {
        profileFile.writeText(json.encodeToString(profile))
        return profile
    }

    fun loadDefaultQaEntries(): List<QABankEntry> {
        val assetText = context.assets.open("qa_bank_defaults.json").bufferedReader().use { it.readText() }
        return json.decodeFromString(assetText)
    }

    // --- Extensible API Key Store ---

    fun getApiKey(keyName: String): String =
        secretsPrefs.getString(keyName, "")?.trim() ?: ""

    fun setApiKey(keyName: String, value: String) {
        val cleaned = value.trim()
        secretsPrefs.edit().apply {
            if (cleaned.isEmpty()) remove(keyName) else putString(keyName, cleaned)
        }.apply()
    }

    fun listStoredKeyNames(): List<String> =
        secretsPrefs.all.keys.sorted()

    private val appPrefs: SharedPreferences by lazy {
        context.getSharedPreferences("job_scout_app_prefs", Context.MODE_PRIVATE)
    }

    fun getDesktopSyncUrl(): String =
        appPrefs.getString("desktop_sync_url", "")?.trim() ?: ""

    fun setDesktopSyncUrl(url: String) {
        appPrefs.edit().putString("desktop_sync_url", url.trim().trimEnd('/')).apply()
    }

    fun maskKey(value: String): String =
        when {
            value.isBlank() -> "Not set"
            value.length >= 6 -> "••••${value.takeLast(4)}"
            else -> "Set"
        }
}
