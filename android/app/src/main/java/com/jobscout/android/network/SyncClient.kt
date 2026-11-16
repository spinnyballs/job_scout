package com.jobscout.android.network

import com.jobscout.android.model.CandidateProfile
import com.jobscout.android.model.JobRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

data class DesktopSyncResult(
    val upserted: Int,
    val markedAppliedOnDesktop: Int,
    val desktopAppliedForPhoneJobs: Int,
    val allAppliedKeys: List<Pair<String, String>>,
    val serverProfile: CandidateProfile?
)

object SyncClient {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    fun normalizeBaseUrl(rawUrl: String): String {
        val trimmed = rawUrl.trim().trimEnd('/')
        require(trimmed.isNotBlank()) { "Enter Desktop Sync URL (e.g. http://192.168.1.50:8765)" }
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            "http://$trimmed"
        }
    }

    suspend fun syncWithDesktop(
        rawUrl: String,
        profile: CandidateProfile?,
        overwriteProfile: Boolean,
        jobs: List<JobRecord>
    ): DesktopSyncResult = withContext(Dispatchers.IO) {
        val baseUrl = normalizeBaseUrl(rawUrl)

        val jobsArray = buildJsonArray {
            for (job in jobs) {
                add(buildJsonObject {
                    put("source", job.source)
                    put("external_id", job.externalId)
                    put("url", job.url)
                    put("title", job.title)
                    put("company", job.company)
                    put("location", job.location)
                    put("work_type", job.workType)
                    put("work_arrangement", job.workArrangement)
                    put("classifications", JsonArray(job.classifications.map { JsonPrimitive(it) }))
                    put("teaser", job.teaser)
                    put("bullet_points", JsonArray(job.bulletPoints.map { JsonPrimitive(it) }))
                    put("description_html", job.descriptionHtml)
                    put("description_text", job.descriptionText)
                    put("salary_label", job.salaryLabel)
                    if (job.salaryEstMin != null) put("salary_est_min", job.salaryEstMin)
                    if (job.salaryEstMax != null) put("salary_est_max", job.salaryEstMax)
                    if (job.postedAt != null) put("posted_at", job.postedAt)
                    if (job.expiresAt != null) put("expires_at", job.expiresAt)
                    put("is_link_out", job.isLinkOut)
                    put("is_expired", job.isExpired)
                    put("screening_questions", JsonArray(job.screeningQuestions.map { JsonPrimitive(it) }))
                    put("matched_queries", JsonArray(job.matchedQueries.map { JsonPrimitive(it) }))
                    put("score", job.score)
                    if (job.scoreBreakdown != null) {
                        put("score_breakdown", json.parseToJsonElement(json.encodeToString(job.scoreBreakdown)))
                    }
                    put("status", job.status.value)
                    put("user_notes", job.userNotes)
                    if (job.llmFit != null) {
                        put("llm_fit", json.parseToJsonElement(json.encodeToString(job.llmFit)))
                    }
                    if (job.tailoredPackage != null) {
                        put("tailored_package", json.parseToJsonElement(json.encodeToString(job.tailoredPackage)))
                    }
                })
            }
        }

        val payload = buildJsonObject {
            if (profile != null) {
                put("profile", json.parseToJsonElement(json.encodeToString(profile)))
            }
            put("overwrite_profile", overwriteProfile)
            put("jobs", jobsArray)
        }

        val req = Request.Builder()
            .url("$baseUrl/api/sync/phone")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val bodyStr = client.newCall(req).execute().use { resp ->
            val text = resp.body?.string() ?: "{}"
            if (!resp.isSuccessful) {
                throw IllegalStateException("Desktop Sync HTTP ${resp.code}: $text")
            }
            text
        }

        val root = json.parseToJsonElement(bodyStr).jsonObject
        val upserted = root["upserted"]?.jsonPrimitive?.intOrNull ?: 0
        val markedAppliedOnDesktop = root["marked_applied_on_desktop"]?.jsonPrimitive?.intOrNull ?: 0
        val appliedUpdatesCount = (root["applied_updates"] as? JsonArray)?.size ?: 0
        val allAppliedKeys = (root["all_applied_keys"] as? JsonArray)?.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val src = obj["source"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val extId = obj["external_id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            src to extId
        } ?: emptyList()

        val serverProfile = root["server_profile"]?.let {
            runCatching { json.decodeFromString<CandidateProfile>(it.toString()) }.getOrNull()
        }

        DesktopSyncResult(
            upserted = upserted,
            markedAppliedOnDesktop = markedAppliedOnDesktop,
            desktopAppliedForPhoneJobs = appliedUpdatesCount,
            allAppliedKeys = allAppliedKeys,
            serverProfile = serverProfile
        )
    }
}
