package com.jobscout.android.network

import com.jobscout.android.domain.ScoringEngine
import com.jobscout.android.model.NormalizedJob
import com.jobscout.android.model.SearchQueryConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class SeekClient(private val requestDelayMs: Int = 220) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }
    private val sessionId = UUID.randomUUID().toString()

    data class SeekBoardConfig(
        val sourceId: String,
        val baseSearchUrl: String,
        val graphqlUrl: String,
        val jobUrlPrefix: String,
        val defaultSiteKey: String,
        val countryCode: String
    )

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        private val AGENCY_HINTS = listOf(
            "recruitment", "recruiting", "recruiters", "talent", "staffing", "personnel",
            "hays", "randstad", "robert walters", "madison", "potentia", "stellar",
            "adecco", "hudson", "beyond recruitment", "accordant", "enterprise recruitment",
            "eighty4", "salt ", "frog recruitment", "kinetic recruitment", "one staff",
            "onestaff", "tradestaff", "awf"
        )

        private const val GQL_JOB_DETAILS_QUERY = """
query jobDetails(${'$'}jobId: ID!, ${'$'}jobDetailsViewedCorrelationId: String!, ${'$'}sessionId: String!) {
  jobDetails(
    id: ${'$'}jobId
    tracking: {channel: "WEB", jobDetailsViewedCorrelationId: ${'$'}jobDetailsViewedCorrelationId, sessionId: ${'$'}sessionId}
  ) {
    job {
      id
      title
      abstract
      content(platform: WEB)
      status
      isLinkOut
      listedAt { dateTimeUtc }
      expiresAt { dateTimeUtc }
      salary { label }
      workTypes { label }
      advertiser { id name }
      location { label }
      classifications { label(languageCode: "en") }
      products { questionnaire { questions } }
    }
  }
}
"""

        val BOARDS = mapOf(
            "seek_nz" to SeekBoardConfig(
                sourceId = "seek_nz",
                baseSearchUrl = "https://www.seek.co.nz/api/jobsearch/v5/search",
                graphqlUrl = "https://nz.seek.com/graphql",
                jobUrlPrefix = "https://nz.seek.com/job",
                defaultSiteKey = "NZ-Main",
                countryCode = "NZ"
            ),
            "seek_au" to SeekBoardConfig(
                sourceId = "seek_au",
                baseSearchUrl = "https://www.seek.com.au/api/jobsearch/v5/search",
                graphqlUrl = "https://www.seek.com.au/graphql",
                jobUrlPrefix = "https://www.seek.com.au/job",
                defaultSiteKey = "AU-Main",
                countryCode = "AU"
            )
        )

        fun parseSeekUrlOrId(rawInput: String, defaultBoard: String = "seek_nz"): Pair<String, String> {
            val text = rawInput.trim()
            require(text.isNotEmpty()) { "Please paste a SEEK job URL or numeric job ID." }
            val lower = text.lowercase()
            val board = when {
                "seek.com.au" in lower || "au.seek.com" in lower -> "seek_au"
                "seek.co.nz" in lower || "nz.seek.com" in lower -> "seek_nz"
                defaultBoard in BOARDS -> defaultBoard
                else -> "seek_nz"
            }
            val patterns = listOf(
                Regex("/job/(\\d{6,12})\\b", RegexOption.IGNORE_CASE),
                Regex("[?&]jobId=(\\d{6,12})\\b", RegexOption.IGNORE_CASE),
                Regex("^(\\d{6,12})$"),
                Regex("\\b(\\d{7,10})\\b")
            )
            for (pat in patterns) {
                val m = pat.find(text)
                if (m != null) return board to m.groupValues[1]
            }
            throw IllegalArgumentException("Could not find a SEEK job ID in that link.")
        }

        fun parseExplicitSalary(label: String): Pair<Int?, Int?> {
            if (label.isBlank()) return null to null
            val lower = label.lowercase()
            val numRe = Regex("\\$\\s*(\\d{2,3}(?:,\\d{3})+|\\d{2,3}(?:\\.\\d+)?\\s*[kK]|\\d{2,3})\\b")
            val matches = numRe.findAll(label).take(2).toList()
            if (matches.isEmpty()) return null to null

            val isHourly = listOf("per hour", "/hr", "p/h", "p.h", "hourly", "/hour").any { it in lower }
            val values = mutableListOf<Int>()
            for (m in matches) {
                val cleaned = m.groupValues[1].replace(",", "").trim().lowercase()
                val valInt: Int? = if (cleaned.endsWith("k")) {
                    (cleaned.dropLast(1).toDoubleOrNull()?.times(1000))?.toInt()
                } else {
                    val num = cleaned.toDoubleOrNull()
                    if (num == null) null
                    else if (isHourly || num < 200) {
                        if (isHourly) (num * 2080).toInt() else if (num >= 50) (num * 1000).toInt() else null
                    } else num.toInt()
                }
                if (valInt != null && valInt in 35000..450000) values.add(valInt)
            }
            return when (values.size) {
                0 -> null to null
                1 -> values[0] to values[0]
                else -> values.min() to values.max()
            }
        }

        private fun looksLikeAgency(company: String): Boolean {
            val c = company.lowercase()
            return AGENCY_HINTS.any { it in c }
        }
    }

    suspend fun search(boardId: String, query: SearchQueryConfig): List<NormalizedJob> = withContext(Dispatchers.IO) {
        val board = BOARDS[boardId] ?: BOARDS.getValue("seek_nz")
        val results = mutableListOf<NormalizedJob>()
        val siteKey = query.siteKey.ifBlank { board.defaultSiteKey }

        for (page in 1..max(1, query.maxPages)) {
            val urlBuilder = board.baseSearchUrl.toHttpUrl().newBuilder()
                .addQueryParameter("siteKey", siteKey)
                .addQueryParameter("keywords", query.keywords)
                .addQueryParameter("page", page.toString())
                .addQueryParameter("pageSize", "22")
                .addQueryParameter("locale", "en-${board.countryCode}")

            if (query.where.isNotBlank()) urlBuilder.addQueryParameter("where", query.where)
            if (query.dateRangeDays != null) urlBuilder.addQueryParameter("daterange", query.dateRangeDays.toString())
            if (!query.workType.isNullOrBlank()) urlBuilder.addQueryParameter("worktype", query.workType)
            if (query.salaryMin != null || query.salaryMax != null) {
                val lo = query.salaryMin ?: 0
                val hi = query.salaryMax ?: 999999
                urlBuilder.addQueryParameter("salaryrange", "$lo-$hi")
                urlBuilder.addQueryParameter("salarytype", "annual")
            }

            val req = Request.Builder()
                .url(urlBuilder.build())
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .get()
                .build()

            val bodyStr = client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IllegalStateException("SEEK search HTTP ${resp.code}")
                resp.body?.string() ?: "{}"
            }
            val payload = json.parseToJsonElement(bodyStr).jsonObject
            val items = payload["data"]?.jsonArray ?: JsonArray(emptyList())
            if (items.isEmpty()) break

            for (el in items) {
                results.add(parseSearchItem(board, el.jsonObject, query.name))
            }

            val totalCount = payload["totalCount"]?.jsonPrimitive?.intOrNull ?: 0
            if (page * 22 >= totalCount) break
            if (requestDelayMs > 0) delay(requestDelayMs.toLong())
        }
        results
    }

    private fun parseSearchItem(board: SeekBoardConfig, raw: JsonObject, queryName: String): NormalizedJob {
        val extId = raw["id"]?.jsonPrimitive?.contentOrNull ?: ""
        val advertiser = raw["advertiser"] as? JsonObject
        val employer = raw["employer"] as? JsonObject
        val company = raw["companyName"]?.jsonPrimitive?.contentOrNull
            ?: employer?.get("name")?.jsonPrimitive?.contentOrNull
            ?: advertiser?.get("description")?.jsonPrimitive?.contentOrNull
            ?: "Private Advertiser"
        val advId = advertiser?.get("id")?.jsonPrimitive?.contentOrNull

        val locations = (raw["locations"] as? JsonArray)?.mapNotNull {
            (it as? JsonObject)?.get("label")?.jsonPrimitive?.contentOrNull
        } ?: emptyList()

        val classifications = (raw["classifications"] as? JsonArray)?.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val mainC = (obj["classification"] as? JsonObject)?.get("description")?.jsonPrimitive?.contentOrNull ?: ""
            val subC = (obj["subclassification"] as? JsonObject)?.get("description")?.jsonPrimitive?.contentOrNull ?: ""
            when {
                subC.isNotBlank() && mainC.isNotBlank() -> "$subC ($mainC)"
                subC.isNotBlank() -> subC
                mainC.isNotBlank() -> mainC
                else -> null
            }
        } ?: emptyList()

        val workTypes = (raw["workTypes"] as? JsonArray)?.mapNotNull {
            (it as? JsonPrimitive)?.contentOrNull
        }?.joinToString(", ") ?: ""

        val workArr = (((raw["workArrangements"] as? JsonObject)?.get("data")) as? JsonArray)?.mapNotNull {
            ((it as? JsonObject)?.get("label") as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull
        }?.joinToString(", ") ?: ""

        val salaryLabel = raw["salaryLabel"]?.jsonPrimitive?.contentOrNull?.trim() ?: ""
        val (salMin, salMax) = parseExplicitSalary(salaryLabel)

        val bullets = (raw["bulletPoints"] as? JsonArray)?.mapNotNull {
            (it as? JsonPrimitive)?.contentOrNull
        } ?: emptyList()

        return NormalizedJob(
            source = board.sourceId,
            externalId = extId,
            url = "${board.jobUrlPrefix}/$extId",
            title = raw["title"]?.jsonPrimitive?.contentOrNull?.trim() ?: "",
            company = company.trim(),
            advertiserId = advId,
            isAgency = looksLikeAgency(company),
            location = locations.joinToString(", "),
            workType = workTypes,
            workArrangement = workArr,
            classifications = classifications,
            teaser = raw["teaser"]?.jsonPrimitive?.contentOrNull?.trim() ?: "",
            bulletPoints = bullets,
            salaryLabel = salaryLabel,
            salaryEstMin = salMin,
            salaryEstMax = salMax,
            postedAt = raw["listingDate"]?.jsonPrimitive?.contentOrNull,
            matchedQueries = mutableListOf(queryName)
        )
    }

    suspend fun enrichDetail(boardId: String, job: NormalizedJob): NormalizedJob = withContext(Dispatchers.IO) {
        val board = BOARDS[boardId] ?: BOARDS.getValue("seek_nz")
        if (requestDelayMs > 0) delay(requestDelayMs.toLong())

        val gqlBody = buildJsonObject {
            put("query", GQL_JOB_DETAILS_QUERY)
            put("variables", buildJsonObject {
                put("jobId", job.externalId)
                put("jobDetailsViewedCorrelationId", UUID.randomUUID().toString())
                put("sessionId", sessionId)
            })
        }

        val req = Request.Builder()
            .url(board.graphqlUrl)
            .header("User-Agent", USER_AGENT)
            .header("Content-Type", "application/json")
            .header("seek-request-brand", "seek")
            .header("seek-request-country", board.countryCode)
            .post(gqlBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val bodyStr = client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("SEEK GraphQL HTTP ${resp.code}")
            resp.body?.string() ?: "{}"
        }
        val root = json.parseToJsonElement(bodyStr).jsonObject
        val detail = ((root["data"] as? JsonObject)?.get("jobDetails") as? JsonObject)?.get("job") as? JsonObject
        if (detail == null) {
            if (job.title.isBlank()) {
                throw IllegalArgumentException("SEEK job ${job.externalId} was not found or is no longer active.")
            }
            return@withContext job
        }

        detail["title"]?.jsonPrimitive?.contentOrNull?.trim()?.let { if (job.title.isBlank()) job.title = it }
        val adv = detail["advertiser"] as? JsonObject
        adv?.get("name")?.jsonPrimitive?.contentOrNull?.trim()?.let {
            if (job.company.isBlank()) {
                job.company = it
                job.isAgency = looksLikeAgency(it)
            }
        }
        adv?.get("id")?.jsonPrimitive?.contentOrNull?.let { if (job.advertiserId == null) job.advertiserId = it }

        (detail["location"] as? JsonObject)?.get("label")?.jsonPrimitive?.contentOrNull?.trim()?.let {
            if (job.location.isBlank()) job.location = it
        }
        (detail["workTypes"] as? JsonObject)?.get("label")?.jsonPrimitive?.contentOrNull?.trim()?.let {
            if (job.workType.isBlank()) job.workType = it
        }
        if (job.classifications.isEmpty()) {
            job.classifications = (detail["classifications"] as? JsonArray)?.mapNotNull {
                (it as? JsonObject)?.get("label")?.jsonPrimitive?.contentOrNull?.trim()
            } ?: emptyList()
        }
        detail["abstract"]?.jsonPrimitive?.contentOrNull?.trim()?.let {
            if (job.teaser.isBlank()) job.teaser = it
        }

        val htmlBody = detail["content"]?.jsonPrimitive?.contentOrNull ?: ""
        job.descriptionHtml = htmlBody
        job.descriptionText = ScoringEngine.htmlToCleanText(htmlBody)
        job.isLinkOut = detail["isLinkOut"]?.jsonPrimitive?.booleanOrNull ?: false
        val status = detail["status"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: ""
        job.isExpired = status !in listOf("active", "")

        (detail["listedAt"] as? JsonObject)?.get("dateTimeUtc")?.jsonPrimitive?.contentOrNull?.let { job.postedAt = it }
        (detail["expiresAt"] as? JsonObject)?.get("dateTimeUtc")?.jsonPrimitive?.contentOrNull?.let { job.expiresAt = it }

        val gqlSal = (detail["salary"] as? JsonObject)?.get("label")?.jsonPrimitive?.contentOrNull?.trim() ?: ""
        if (gqlSal.isNotBlank() && job.salaryLabel.isBlank()) {
            job.salaryLabel = gqlSal
            val (smin, smax) = parseExplicitSalary(gqlSal)
            if (smin != null) {
                job.salaryEstMin = smin
                job.salaryEstMax = smax
            }
        }

        val qs = (((detail["products"] as? JsonObject)?.get("questionnaire") as? JsonObject)?.get("questions")) as? JsonArray
        if (qs != null) {
            job.screeningQuestions = qs.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        }
        job
    }

    private suspend fun jobInSalaryBand(board: SeekBoardConfig, job: NormalizedJob, low: Int, high: Int): Boolean =
        withContext(Dispatchers.IO) {
            delay(120)
            val cleanTitle = job.title.replace(Regex("[^\\w\\s&+/-]"), " ").trim()
            val urlBuilder = board.baseSearchUrl.toHttpUrl().newBuilder()
                .addQueryParameter("siteKey", board.defaultSiteKey)
                .addQueryParameter("keywords", "\"$cleanTitle\"")
                .addQueryParameter("salaryrange", "$low-$high")
                .addQueryParameter("salarytype", "annual")
                .addQueryParameter("pageSize", "50")

            if (!job.advertiserId.isNullOrBlank()) {
                urlBuilder.addQueryParameter("advertiserid", job.advertiserId)
            } else if (job.location.isNotBlank()) {
                urlBuilder.addQueryParameter("where", job.location.split(",").last().trim())
            }

            val req = Request.Builder()
                .url(urlBuilder.build())
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .get()
                .build()

            val bodyStr = client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext false
                resp.body?.string() ?: "{}"
            }
            val items = json.parseToJsonElement(bodyStr).jsonObject["data"]?.jsonArray ?: return@withContext false
            items.any { (it as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull == job.externalId }
        }

    suspend fun probeSalary(boardId: String, job: NormalizedJob): Pair<Int?, Int?> {
        val board = BOARDS[boardId] ?: BOARDS.getValue("seek_nz")
        if (!jobInSalaryBand(board, job, 0, 999999)) {
            return job.salaryEstMin to job.salaryEstMax
        }

        var lo = 45000
        var hi = 260000
        val estMin = if (jobInSalaryBand(board, job, 0, lo)) {
            lo
        } else {
            while (hi - lo > 2500) {
                val mid = ((lo + hi) / 2 / 1000) * 1000
                if (jobInSalaryBand(board, job, 0, mid)) hi = mid else lo = mid
            }
            ((hi / 1000.0).roundToInt()) * 1000
        }

        var lo2 = estMin
        var hi2 = min(estMin + 120000, 320000)
        val estMax = if (jobInSalaryBand(board, job, hi2, 999999)) {
            hi2
        } else {
            while (hi2 - lo2 > 2500) {
                val mid = ((lo2 + hi2) / 2 / 1000) * 1000
                if (jobInSalaryBand(board, job, mid, 999999)) lo2 = mid else hi2 = mid
            }
            ((lo2 / 1000.0).roundToInt()) * 1000
        }

        return estMin to max(estMin, estMax)
    }
}
