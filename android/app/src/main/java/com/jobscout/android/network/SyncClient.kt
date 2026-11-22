package com.jobscout.android.network

import com.jobscout.android.model.CandidateProfile
import com.jobscout.android.model.DesktopPipelineJobDto
import com.jobscout.android.model.DesktopStatusUpdate
import com.jobscout.android.model.JobRecord
import com.jobscout.android.model.JobStatus
import com.jobscout.android.model.LLMFitAnalysis
import com.jobscout.android.model.ScoreBreakdown
import com.jobscout.android.model.TailoredApplicationPayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
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
    val statusUpdates: List<DesktopStatusUpdate>,
    val desktopJobs: List<DesktopPipelineJobDto>,
    val expiredRemovedKeys: List<Pair<String, String>>,
    val availabilityChecked: Int,
    val expiredMarkedOnDesktop: Int,
    val expiredRemovedOnDesktop: Int,
    val serverProfile: CandidateProfile?
)

object SyncClient {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
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

    fun parseSyncResponseJson(bodyStr: String): DesktopSyncResult {
        val root = json.parseToJsonElement(bodyStr).jsonObject
        val upserted = root["upserted"]?.jsonPrimitive?.intOrNull ?: 0
        val markedAppliedOnDesktop = root["marked_applied_on_desktop"]?.jsonPrimitive?.intOrNull ?: 0
        val appliedUpdatesCount = (root["applied_updates"] as? JsonArray)?.size ?: 0
        val availabilityChecked = root["availability_checked"]?.jsonPrimitive?.intOrNull ?: 0
        val expiredMarkedOnDesktop = root["expired_marked"]?.jsonPrimitive?.intOrNull ?: 0
        val expiredRemovedOnDesktop = root["expired_removed"]?.jsonPrimitive?.intOrNull ?: 0

        val allAppliedKeys = (root["all_applied_keys"] as? JsonArray)?.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val src = obj["source"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val extId = obj["external_id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            src to extId
        } ?: emptyList()

        val expiredRemovedKeys = (root["expired_removed_keys"] as? JsonArray)?.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val src = obj["source"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val extId = obj["external_id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            src to extId
        } ?: emptyList()

        val statusUpdates = (root["status_updates"] as? JsonArray)?.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val src = obj["source"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val extId = obj["external_id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val stStr = obj["status"]?.jsonPrimitive?.contentOrNull ?: "discovered"
            DesktopStatusUpdate(
                source = src,
                externalId = extId,
                status = JobStatus.fromValue(stStr),
                statusUpdatedAt = obj["status_updated_at"]?.jsonPrimitive?.contentOrNull ?: "",
                appliedAt = obj["applied_at"]?.jsonPrimitive?.contentOrNull,
                isExpired = obj["is_expired"]?.jsonPrimitive?.booleanOrNull ?: false,
                expiresAt = obj["expires_at"]?.jsonPrimitive?.contentOrNull,
                blockedReason = obj["blocked_reason"]?.jsonPrimitive?.contentOrNull
            )
        } ?: emptyList()

        val desktopJobs = (root["desktop_jobs"] as? JsonArray)?.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val src = obj["source"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val extId = obj["external_id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            fun strList(key: String): List<String> =
                (obj[key] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()

            val scoreBd = obj["score_breakdown"]?.takeIf { it is JsonObject }?.let {
                runCatching { json.decodeFromString<ScoreBreakdown>(it.toString()) }.getOrNull()
            }
            val llmFit = obj["llm_fit"]?.takeIf { it is JsonObject }?.let {
                runCatching { json.decodeFromString<LLMFitAnalysis>(it.toString()) }.getOrNull()
            }
            val tailoredObj = obj["tailored_package"] as? JsonObject
            val tailoredPkg = tailoredObj?.let {
                runCatching { json.decodeFromString<TailoredApplicationPayload>(it.toString()) }.getOrNull()
            }
            val modelUsed = tailoredObj?.get("model_used")?.jsonPrimitive?.contentOrNull ?: ""

            DesktopPipelineJobDto(
                source = src,
                externalId = extId,
                url = obj["url"]?.jsonPrimitive?.contentOrNull ?: "",
                title = obj["title"]?.jsonPrimitive?.contentOrNull ?: "",
                company = obj["company"]?.jsonPrimitive?.contentOrNull ?: "",
                advertiserId = obj["advertiser_id"]?.jsonPrimitive?.contentOrNull,
                isAgency = obj["is_agency"]?.jsonPrimitive?.booleanOrNull ?: false,
                location = obj["location"]?.jsonPrimitive?.contentOrNull ?: "",
                workType = obj["work_type"]?.jsonPrimitive?.contentOrNull ?: "",
                workArrangement = obj["work_arrangement"]?.jsonPrimitive?.contentOrNull ?: "",
                classifications = strList("classifications"),
                teaser = obj["teaser"]?.jsonPrimitive?.contentOrNull ?: "",
                bulletPoints = strList("bullet_points"),
                descriptionHtml = obj["description_html"]?.jsonPrimitive?.contentOrNull ?: "",
                descriptionText = obj["description_text"]?.jsonPrimitive?.contentOrNull ?: "",
                salaryLabel = obj["salary_label"]?.jsonPrimitive?.contentOrNull ?: "",
                salaryEstMin = obj["salary_est_min"]?.jsonPrimitive?.intOrNull,
                salaryEstMax = obj["salary_est_max"]?.jsonPrimitive?.intOrNull,
                postedAt = obj["posted_at"]?.jsonPrimitive?.contentOrNull,
                expiresAt = obj["expires_at"]?.jsonPrimitive?.contentOrNull,
                isLinkOut = obj["is_link_out"]?.jsonPrimitive?.booleanOrNull ?: false,
                isExpired = obj["is_expired"]?.jsonPrimitive?.booleanOrNull ?: false,
                screeningQuestions = strList("screening_questions"),
                matchedQueries = strList("matched_queries"),
                score = obj["score"]?.jsonPrimitive?.intOrNull ?: 0,
                scoreBreakdown = scoreBd,
                blockedReason = obj["blocked_reason"]?.jsonPrimitive?.contentOrNull,
                status = JobStatus.fromValue(obj["status"]?.jsonPrimitive?.contentOrNull),
                statusUpdatedAt = obj["status_updated_at"]?.jsonPrimitive?.contentOrNull ?: "",
                appliedAt = obj["applied_at"]?.jsonPrimitive?.contentOrNull,
                userNotes = obj["user_notes"]?.jsonPrimitive?.contentOrNull ?: "",
                llmFit = llmFit,
                tailoredPackage = tailoredPkg,
                tailoredModelUsed = modelUsed,
                syncedFromPhone = obj["synced_from_phone"]?.jsonPrimitive?.booleanOrNull ?: false
            )
        } ?: emptyList()

        val serverProfile = root["server_profile"]?.let {
            runCatching { json.decodeFromString<CandidateProfile>(it.toString()) }.getOrNull()
        }

        return DesktopSyncResult(
            upserted = upserted,
            markedAppliedOnDesktop = markedAppliedOnDesktop,
            desktopAppliedForPhoneJobs = appliedUpdatesCount,
            allAppliedKeys = allAppliedKeys,
            statusUpdates = statusUpdates,
            desktopJobs = desktopJobs,
            expiredRemovedKeys = expiredRemovedKeys,
            availabilityChecked = availabilityChecked,
            expiredMarkedOnDesktop = expiredMarkedOnDesktop,
            expiredRemovedOnDesktop = expiredRemovedOnDesktop,
            serverProfile = serverProfile
        )
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
                    if (!job.blockedReason.isNullOrBlank()) {
                        put("blocked_reason", job.blockedReason)
                    }
                    put("status", job.status.value)
                    put("status_updated_at", job.statusUpdatedAt)
                    if (!job.appliedAt.isNullOrBlank()) {
                        put("applied_at", job.appliedAt)
                    }
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

        parseSyncResponseJson(bodyStr)
    }
}
