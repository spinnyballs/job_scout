package com.jobscout.android.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.jobscout.android.domain.ScoringEngine
import com.jobscout.android.model.DesktopPipelineJobDto
import com.jobscout.android.model.DesktopStatusUpdate
import com.jobscout.android.model.JobRecord
import com.jobscout.android.model.JobStatus
import com.jobscout.android.model.LLMFitAnalysis
import com.jobscout.android.model.NormalizedJob
import com.jobscout.android.model.QABankEntry
import com.jobscout.android.model.ScoreBreakdown
import com.jobscout.android.model.SyncMergeSummary
import com.jobscout.android.model.TailoredApplicationPayload
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant

class JobDatabase(
    context: Context,
    private val defaultQaSupplier: () -> List<QABankEntry>
) : SQLiteOpenHelper(context, "jobs.db", null, 2) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS jobs (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                source TEXT NOT NULL,
                external_id TEXT NOT NULL,
                dedup_key TEXT NOT NULL,
                url TEXT NOT NULL,
                title TEXT NOT NULL,
                company TEXT NOT NULL,
                advertiser_id TEXT,
                is_agency INTEGER NOT NULL DEFAULT 0,
                location TEXT DEFAULT '',
                work_type TEXT DEFAULT '',
                work_arrangement TEXT DEFAULT '',
                classifications_json TEXT DEFAULT '[]',
                teaser TEXT DEFAULT '',
                bullet_points_json TEXT DEFAULT '[]',
                description_html TEXT DEFAULT '',
                description_text TEXT DEFAULT '',
                salary_label TEXT DEFAULT '',
                salary_est_min INTEGER,
                salary_est_max INTEGER,
                posted_at TEXT,
                expires_at TEXT,
                is_link_out INTEGER NOT NULL DEFAULT 0,
                is_expired INTEGER NOT NULL DEFAULT 0,
                screening_questions_json TEXT DEFAULT '[]',
                matched_queries_json TEXT DEFAULT '[]',
                score INTEGER NOT NULL DEFAULT 0,
                score_breakdown_json TEXT DEFAULT '{}',
                blocked_reason TEXT,
                status TEXT NOT NULL DEFAULT 'discovered',
                status_updated_at TEXT DEFAULT '',
                applied_at TEXT,
                synced_from_desktop INTEGER NOT NULL DEFAULT 0,
                user_notes TEXT DEFAULT '',
                first_seen_at TEXT NOT NULL,
                last_seen_at TEXT NOT NULL,
                llm_fit_json TEXT DEFAULT '',
                llm_fit_score INTEGER,
                tailored_package_json TEXT DEFAULT '',
                tailored_model_used TEXT DEFAULT '',
                cv_pdf_path TEXT DEFAULT '',
                cover_letter_pdf_path TEXT DEFAULT '',
                UNIQUE(source, external_id)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_jobs_score ON jobs(score DESC)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_jobs_status ON jobs(status)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS qa_bank (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                canonical_key TEXT NOT NULL UNIQUE,
                question_pattern TEXT NOT NULL,
                answer TEXT NOT NULL,
                category TEXT NOT NULL DEFAULT 'general',
                notes TEXT DEFAULT '',
                updated_at TEXT NOT NULL
            )
            """.trimIndent()
        )

        val now = Instant.now().toString()
        for (entry in defaultQaSupplier()) {
            val cv = ContentValues().apply {
                put("canonical_key", entry.canonicalKey)
                put("question_pattern", entry.questionPattern)
                put("answer", entry.answer)
                put("category", entry.category)
                put("notes", entry.notes)
                put("updated_at", now)
            }
            db.insertWithOnConflict("qa_bank", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
        }
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            ensureColumn(db, "jobs", "status_updated_at", "TEXT DEFAULT ''")
            ensureColumn(db, "jobs", "applied_at", "TEXT")
            ensureColumn(db, "jobs", "synced_from_desktop", "INTEGER NOT NULL DEFAULT 0")
        }
    }

    private fun ensureColumn(db: SQLiteDatabase, table: String, col: String, ddl: String) {
        val existingCols = mutableSetOf<String>()
        db.rawQuery("PRAGMA table_info($table)", null).use { c ->
            val nameIdx = c.getColumnIndex("name")
            while (c.moveToNext()) {
                if (nameIdx >= 0) existingCols.add(c.getString(nameIdx))
            }
        }
        if (col !in existingCols) {
            db.execSQL("ALTER TABLE $table ADD COLUMN $col $ddl")
        }
    }

    fun getExistingRaw(source: String, externalId: String): JobRecord? {
        val db = readableDatabase
        db.rawQuery(
            "SELECT * FROM jobs WHERE source = ? AND external_id = ?",
            arrayOf(source, externalId)
        ).use { c ->
            return if (c.moveToFirst()) cursorToJob(c) else null
        }
    }

    fun getJob(id: Long): JobRecord? {
        val db = readableDatabase
        db.rawQuery("SELECT * FROM jobs WHERE id = ?", arrayOf(id.toString())).use { c ->
            return if (c.moveToFirst()) cursorToJob(c) else null
        }
    }

    fun upsertJob(job: NormalizedJob, breakdown: ScoreBreakdown): Pair<Long, Boolean> {
        val now = Instant.now().toString()
        val dedupKey = ScoringEngine.computeDedupKey(job.title, job.company, job.location)
        val existing = getExistingRaw(job.source, job.externalId)
        val db = writableDatabase

        if (existing == null) {
            val status = if (breakdown.blockedReason != null) JobStatus.BLOCKED.value else JobStatus.DISCOVERED.value
            val cv = ContentValues().apply {
                put("source", job.source)
                put("external_id", job.externalId)
                put("dedup_key", dedupKey)
                put("url", job.url)
                put("title", job.title)
                put("company", job.company)
                put("advertiser_id", job.advertiserId)
                put("is_agency", if (job.isAgency) 1 else 0)
                put("location", job.location)
                put("work_type", job.workType)
                put("work_arrangement", job.workArrangement)
                put("classifications_json", json.encodeToString(job.classifications))
                put("teaser", job.teaser)
                put("bullet_points_json", json.encodeToString(job.bulletPoints))
                put("description_html", job.descriptionHtml)
                put("description_text", job.descriptionText)
                put("salary_label", job.salaryLabel)
                if (job.salaryEstMin != null) put("salary_est_min", job.salaryEstMin)
                if (job.salaryEstMax != null) put("salary_est_max", job.salaryEstMax)
                put("posted_at", job.postedAt)
                put("expires_at", job.expiresAt)
                put("is_link_out", if (job.isLinkOut) 1 else 0)
                put("is_expired", if (job.isExpired) 1 else 0)
                put("screening_questions_json", json.encodeToString(job.screeningQuestions))
                put("matched_queries_json", json.encodeToString(job.matchedQueries))
                put("score", breakdown.score)
                put("score_breakdown_json", json.encodeToString(breakdown))
                put("blocked_reason", breakdown.blockedReason)
                put("status", status)
                put("status_updated_at", now)
                put("first_seen_at", now)
                put("last_seen_at", now)
            }
            val rowId = db.insert("jobs", null, cv)
            return rowId to true
        } else {
            val mergedQueries = (existing.matchedQueries + job.matchedQueries).distinct()
            val newStatus = when {
                breakdown.blockedReason != null -> {
                    if (existing.status.isProtectedPipeline) existing.status.value else JobStatus.BLOCKED.value
                }
                existing.status == JobStatus.BLOCKED -> JobStatus.DISCOVERED.value
                else -> existing.status.value
            }
            val statusChanged = newStatus != existing.status.value
            val isExpiredFlag = job.isExpired || existing.isExpired
            val cv = ContentValues().apply {
                if (job.url.isNotBlank()) put("url", job.url)
                put("title", job.title)
                put("company", job.company)
                put("location", job.location)
                put("work_type", job.workType)
                put("work_arrangement", job.workArrangement)
                put("teaser", job.teaser.ifBlank { existing.teaser })
                if (job.bulletPoints.isNotEmpty()) {
                    put("bullet_points_json", json.encodeToString(job.bulletPoints))
                }
                if (job.descriptionHtml.isNotBlank()) put("description_html", job.descriptionHtml)
                if (job.descriptionText.isNotBlank()) put("description_text", job.descriptionText)
                if (job.salaryLabel.isNotBlank()) put("salary_label", job.salaryLabel)
                if (job.salaryEstMin != null) put("salary_est_min", job.salaryEstMin)
                if (job.salaryEstMax != null) put("salary_est_max", job.salaryEstMax)
                if (!job.expiresAt.isNullOrBlank()) put("expires_at", job.expiresAt)
                put("is_link_out", if (job.isLinkOut) 1 else 0)
                put("is_expired", if (isExpiredFlag) 1 else 0)
                if (job.screeningQuestions.isNotEmpty()) {
                    put("screening_questions_json", json.encodeToString(job.screeningQuestions))
                }
                put("matched_queries_json", json.encodeToString(mergedQueries))
                put("score", breakdown.score)
                put("score_breakdown_json", json.encodeToString(breakdown))
                put("blocked_reason", breakdown.blockedReason)
                put("status", newStatus)
                if (statusChanged) put("status_updated_at", now)
                put("last_seen_at", now)
            }
            db.update("jobs", cv, "id = ?", arrayOf(existing.id.toString()))
            return existing.id to false
        }
    }

    fun listJobs(
        search: String = "",
        statusFilter: String? = null,
        minScore: Int? = null,
        includeBlocked: Boolean = false
    ): List<JobRecord> {
        val where = mutableListOf<String>()
        val args = mutableListOf<String>()
        if (!statusFilter.isNullOrBlank() && statusFilter != "all") {
            val parts = statusFilter.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.size == 1) {
                where.add("status = ?")
                args.add(parts[0])
            } else if (parts.isNotEmpty()) {
                val placeholders = parts.joinToString(", ") { "?" }
                where.add("status IN ($placeholders)")
                args.addAll(parts)
            }
        } else if (!includeBlocked) {
            where.add("status NOT IN ('blocked', 'skipped', 'no_reply', 'rejected')")
        }
        if (minScore != null) {
            where.add("score >= ?")
            args.add(minScore.toString())
        }
        if (search.isNotBlank()) {
            where.add("(title LIKE ? OR company LIKE ? OR location LIKE ?)")
            val q = "%${search.trim()}%"
            args.add(q); args.add(q); args.add(q)
        }
        val whereSql = if (where.isEmpty()) "" else "WHERE " + where.joinToString(" AND ")
        val sql = "SELECT * FROM jobs $whereSql ORDER BY score DESC, posted_at DESC, id DESC LIMIT 250"
        val out = mutableListOf<JobRecord>()
        readableDatabase.rawQuery(sql, args.toTypedArray()).use { c ->
            while (c.moveToNext()) out.add(cursorToJob(c))
        }
        return out
    }

    fun updateSalary(jobId: Long, salMin: Int?, salMax: Int?, breakdown: ScoreBreakdown?) {
        val cv = ContentValues().apply {
            if (salMin != null) put("salary_est_min", salMin) else putNull("salary_est_min")
            if (salMax != null) put("salary_est_max", salMax) else putNull("salary_est_max")
            if (breakdown != null) {
                put("score", breakdown.score)
                put("score_breakdown_json", json.encodeToString(breakdown))
            }
        }
        writableDatabase.update("jobs", cv, "id = ?", arrayOf(jobId.toString()))
    }

    fun updateStatus(jobId: Long, status: JobStatus) {
        val now = Instant.now().toString()
        val existing = getJob(jobId)
        val cv = ContentValues().apply {
            put("status", status.value)
            put("status_updated_at", now)
            if (status.isAppliedOutcome && existing?.appliedAt.isNullOrBlank()) {
                put("applied_at", now)
            }
        }
        writableDatabase.update("jobs", cv, "id = ?", arrayOf(jobId.toString()))
    }

    fun rescoreAll(config: com.jobscout.android.model.ScannerConfig): Int {
        val allJobs = mutableListOf<JobRecord>()
        readableDatabase.rawQuery("SELECT * FROM jobs", null).use { c ->
            while (c.moveToNext()) allJobs.add(cursorToJob(c))
        }
        val db = writableDatabase
        val now = Instant.now().toString()
        var count = 0
        db.beginTransaction()
        try {
            for (rec in allJobs) {
                val norm = NormalizedJob(
                    source = rec.source,
                    externalId = rec.externalId,
                    url = rec.url,
                    title = rec.title,
                    company = rec.company,
                    advertiserId = rec.advertiserId,
                    isAgency = rec.isAgency,
                    location = rec.location,
                    workType = rec.workType,
                    workArrangement = rec.workArrangement,
                    classifications = rec.classifications,
                    teaser = rec.teaser,
                    bulletPoints = rec.bulletPoints,
                    descriptionHtml = rec.descriptionHtml,
                    descriptionText = rec.descriptionText,
                    salaryLabel = rec.salaryLabel,
                    salaryEstMin = rec.salaryEstMin,
                    salaryEstMax = rec.salaryEstMax,
                    postedAt = rec.postedAt,
                    expiresAt = rec.expiresAt,
                    isLinkOut = rec.isLinkOut,
                    isExpired = rec.isExpired,
                    screeningQuestions = rec.screeningQuestions,
                    matchedQueries = rec.matchedQueries.toMutableList()
                )
                val bd = ScoringEngine.scoreJob(norm, config)
                val newStatus = when {
                    bd.blockedReason != null -> {
                        if (rec.status.isProtectedPipeline) rec.status.value else JobStatus.BLOCKED.value
                    }
                    rec.status == JobStatus.BLOCKED -> JobStatus.DISCOVERED.value
                    else -> rec.status.value
                }
                val cv = ContentValues().apply {
                    put("score", bd.score)
                    put("score_breakdown_json", json.encodeToString(bd))
                    put("blocked_reason", bd.blockedReason)
                    put("status", newStatus)
                    if (newStatus != rec.status.value) {
                        put("status_updated_at", now)
                    }
                }
                db.update("jobs", cv, "id = ?", arrayOf(rec.id.toString()))
                count++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return count
    }

    fun saveLlmFit(jobId: Long, fit: LLMFitAnalysis) {
        val cv = ContentValues().apply {
            put("llm_fit_json", json.encodeToString(fit))
            put("llm_fit_score", fit.fitScore)
        }
        writableDatabase.update("jobs", cv, "id = ?", arrayOf(jobId.toString()))
    }

    fun saveTailoredPackage(
        jobId: Long,
        payload: TailoredApplicationPayload,
        modelUsed: String,
        cvPdfPath: String?,
        clPdfPath: String?
    ) {
        val now = Instant.now().toString()
        val existing = getJob(jobId)
        val newStatus = if (existing?.status == JobStatus.DISCOVERED || existing?.status == JobStatus.SHORTLISTED) {
            JobStatus.TAILORED.value
        } else {
            existing?.status?.value ?: JobStatus.TAILORED.value
        }
        val cv = ContentValues().apply {
            put("tailored_package_json", json.encodeToString(payload))
            put("tailored_model_used", modelUsed)
            put("cv_pdf_path", cvPdfPath ?: "")
            put("cover_letter_pdf_path", clPdfPath ?: "")
            put("status", newStatus)
            put("status_updated_at", now)
        }
        writableDatabase.update("jobs", cv, "id = ?", arrayOf(jobId.toString()))
    }

    fun clearTailoredPackage(jobId: Long) {
        val now = Instant.now().toString()
        val cv = ContentValues().apply {
            put("tailored_package_json", "")
            put("tailored_model_used", "")
            put("cv_pdf_path", "")
            put("cover_letter_pdf_path", "")
            put("status", JobStatus.SHORTLISTED.value)
            put("status_updated_at", now)
        }
        writableDatabase.update("jobs", cv, "id = ?", arrayOf(jobId.toString()))
    }

    fun listJobsForSync(): List<JobRecord> {
        val out = mutableListOf<JobRecord>()
        readableDatabase.rawQuery(
            "SELECT * FROM jobs WHERE status != 'discovered'",
            null
        ).use { c ->
            while (c.moveToNext()) out.add(cursorToJob(c))
        }
        return out
    }

    fun mergeDesktopSyncPayload(
        desktopJobs: List<DesktopPipelineJobDto>,
        statusUpdates: List<DesktopStatusUpdate>,
        expiredRemovedKeys: List<Pair<String, String>>
    ): SyncMergeSummary {
        var insertedFromDesktop = 0
        var statusUpdatedOnPhone = 0
        var expiredFlaggedOnPhone = 0
        var expiredRemovedOnPhone = 0
        val now = Instant.now().toString()
        val db = writableDatabase

        db.beginTransaction()
        try {
            // 1. Delete expired non-pipeline jobs that Desktop pruned
            for ((src, extId) in expiredRemovedKeys) {
                val existing = getExistingRaw(src, extId) ?: continue
                if (!existing.status.isProtectedPipeline) {
                    val del = db.delete(
                        "jobs",
                        "id = ? AND status IN ('discovered', 'skipped', 'blocked')",
                        arrayOf(existing.id.toString())
                    )
                    expiredRemovedOnPhone += del
                } else if (!existing.isExpired) {
                    val cv = ContentValues().apply { put("is_expired", 1) }
                    db.update("jobs", cv, "id = ?", arrayOf(existing.id.toString()))
                    expiredFlaggedOnPhone++
                }
            }

            // 2. Upsert full Desktop pipeline jobs (shortlisted, tailored, applied, interview, no_reply, rejected) without PDFs
            for (dJob in desktopJobs) {
                if (dJob.externalId.isBlank()) continue
                val existing = getExistingRaw(dJob.source, dJob.externalId)
                if (existing == null) {
                    val dedupKey = ScoringEngine.computeDedupKey(dJob.title, dJob.company, dJob.location)
                    val cv = ContentValues().apply {
                        put("source", dJob.source)
                        put("external_id", dJob.externalId)
                        put("dedup_key", dedupKey)
                        put("url", dJob.url)
                        put("title", dJob.title)
                        put("company", dJob.company)
                        put("advertiser_id", dJob.advertiserId)
                        put("is_agency", if (dJob.isAgency) 1 else 0)
                        put("location", dJob.location)
                        put("work_type", dJob.workType)
                        put("work_arrangement", dJob.workArrangement)
                        put("classifications_json", json.encodeToString(dJob.classifications))
                        put("teaser", dJob.teaser)
                        put("bullet_points_json", json.encodeToString(dJob.bulletPoints))
                        put("description_html", dJob.descriptionHtml)
                        put("description_text", dJob.descriptionText)
                        put("salary_label", dJob.salaryLabel)
                        if (dJob.salaryEstMin != null) put("salary_est_min", dJob.salaryEstMin)
                        if (dJob.salaryEstMax != null) put("salary_est_max", dJob.salaryEstMax)
                        put("posted_at", dJob.postedAt)
                        put("expires_at", dJob.expiresAt)
                        put("is_link_out", if (dJob.isLinkOut) 1 else 0)
                        put("is_expired", if (dJob.isExpired) 1 else 0)
                        put("screening_questions_json", json.encodeToString(dJob.screeningQuestions))
                        put("matched_queries_json", json.encodeToString(dJob.matchedQueries))
                        put("score", dJob.score)
                        if (dJob.scoreBreakdown != null) {
                            put("score_breakdown_json", json.encodeToString(dJob.scoreBreakdown))
                        }
                        put("blocked_reason", dJob.blockedReason)
                        put("status", dJob.status.value)
                        put("status_updated_at", dJob.statusUpdatedAt.ifBlank { now })
                        if (!dJob.appliedAt.isNullOrBlank()) put("applied_at", dJob.appliedAt)
                        put("synced_from_desktop", if (dJob.syncedFromPhone) 0 else 1)
                        put("user_notes", dJob.userNotes)
                        put("first_seen_at", now)
                        put("last_seen_at", now)
                        if (dJob.llmFit != null) {
                            put("llm_fit_json", json.encodeToString(dJob.llmFit))
                            put("llm_fit_score", dJob.llmFit.fitScore)
                        }
                        if (dJob.tailoredPackage != null) {
                            put("tailored_package_json", json.encodeToString(dJob.tailoredPackage))
                            put("tailored_model_used", dJob.tailoredModelUsed.ifBlank { "Desktop AI" })
                        }
                    }
                    db.insertWithOnConflict("jobs", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
                    insertedFromDesktop++
                    if (dJob.isExpired) expiredFlaggedOnPhone++
                } else {
                    val cv = ContentValues().apply {
                        if (existing.descriptionHtml.isBlank() && dJob.descriptionHtml.isNotBlank()) {
                            put("description_html", dJob.descriptionHtml)
                        }
                        if (existing.descriptionText.isBlank() && dJob.descriptionText.isNotBlank()) {
                            put("description_text", dJob.descriptionText)
                        }
                        if (existing.salaryEstMin == null && dJob.salaryEstMin != null) {
                            put("salary_est_min", dJob.salaryEstMin)
                        }
                        if (existing.salaryEstMax == null && dJob.salaryEstMax != null) {
                            put("salary_est_max", dJob.salaryEstMax)
                        }
                        if (existing.llmFit == null && dJob.llmFit != null) {
                            put("llm_fit_json", json.encodeToString(dJob.llmFit))
                            put("llm_fit_score", dJob.llmFit.fitScore)
                        }
                        if (existing.tailoredPackage == null && dJob.tailoredPackage != null) {
                            put("tailored_package_json", json.encodeToString(dJob.tailoredPackage))
                            put("tailored_model_used", dJob.tailoredModelUsed.ifBlank { "Desktop AI" })
                        }
                        if (existing.userNotes.isBlank() && dJob.userNotes.isNotBlank()) {
                            put("user_notes", dJob.userNotes)
                        }
                        if (!dJob.expiresAt.isNullOrBlank()) {
                            put("expires_at", dJob.expiresAt)
                        }
                        if (dJob.isExpired && !existing.isExpired) {
                            put("is_expired", 1)
                            expiredFlaggedOnPhone++
                        }
                        if (existing.status != dJob.status) {
                            put("status", dJob.status.value)
                            put("status_updated_at", dJob.statusUpdatedAt.ifBlank { now })
                            statusUpdatedOnPhone++
                        } else if (existing.statusUpdatedAt.isBlank() && dJob.statusUpdatedAt.isNotBlank()) {
                            put("status_updated_at", dJob.statusUpdatedAt)
                        }
                        if (existing.appliedAt.isNullOrBlank() && !dJob.appliedAt.isNullOrBlank()) {
                            put("applied_at", dJob.appliedAt)
                        }
                    }
                    if (cv.size() > 0) {
                        db.update("jobs", cv, "id = ?", arrayOf(existing.id.toString()))
                    }
                }
            }

            // 3. Apply authoritative status updates (including Desktop 'skipped', expiry flags, etc.)
            for (upd in statusUpdates) {
                val existing = getExistingRaw(upd.source, upd.externalId) ?: continue
                // Guardrail rule: never demote a protected pipeline job to blocked
                val targetStatus = if (upd.status == JobStatus.BLOCKED && existing.status.isProtectedPipeline) {
                    existing.status
                } else {
                    upd.status
                }
                val cv = ContentValues()
                if (existing.status != targetStatus) {
                    cv.put("status", targetStatus.value)
                    cv.put("status_updated_at", upd.statusUpdatedAt.ifBlank { now })
                    statusUpdatedOnPhone++
                } else if (existing.statusUpdatedAt.isBlank() && upd.statusUpdatedAt.isNotBlank()) {
                    cv.put("status_updated_at", upd.statusUpdatedAt)
                }
                if (!upd.appliedAt.isNullOrBlank() && existing.appliedAt.isNullOrBlank()) {
                    cv.put("applied_at", upd.appliedAt)
                }
                if (!upd.expiresAt.isNullOrBlank() && existing.expiresAt.isNullOrBlank()) {
                    cv.put("expires_at", upd.expiresAt)
                }
                if (upd.isExpired && !existing.isExpired) {
                    cv.put("is_expired", 1)
                    expiredFlaggedOnPhone++
                }
                if (cv.size() > 0) {
                    db.update("jobs", cv, "id = ?", arrayOf(existing.id.toString()))
                }
            }

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        return SyncMergeSummary(
            insertedFromDesktop = insertedFromDesktop,
            statusUpdatedOnPhone = statusUpdatedOnPhone,
            expiredFlaggedOnPhone = expiredFlaggedOnPhone,
            expiredRemovedOnPhone = expiredRemovedOnPhone
        )
    }

    fun listQaEntries(): List<QABankEntry> {
        val out = mutableListOf<QABankEntry>()
        readableDatabase.rawQuery("SELECT * FROM qa_bank ORDER BY category, canonical_key", null).use { c ->
            while (c.moveToNext()) {
                out.add(
                    QABankEntry(
                        id = c.getLong(c.getColumnIndexOrThrow("id")),
                        canonicalKey = c.getString(c.getColumnIndexOrThrow("canonical_key")),
                        questionPattern = c.getString(c.getColumnIndexOrThrow("question_pattern")),
                        answer = c.getString(c.getColumnIndexOrThrow("answer")),
                        category = c.getString(c.getColumnIndexOrThrow("category")),
                        notes = c.getString(c.getColumnIndexOrThrow("notes")) ?: "",
                        updatedAt = c.getString(c.getColumnIndexOrThrow("updated_at"))
                    )
                )
            }
        }
        return out
    }

    fun upsertQaEntry(entry: QABankEntry) {
        val now = Instant.now().toString()
        val cv = ContentValues().apply {
            put("canonical_key", entry.canonicalKey)
            put("question_pattern", entry.questionPattern)
            put("answer", entry.answer)
            put("category", entry.category)
            put("notes", entry.notes)
            put("updated_at", now)
        }
        if (entry.id != null) {
            writableDatabase.update("qa_bank", cv, "id = ?", arrayOf(entry.id.toString()))
        } else {
            writableDatabase.insertWithOnConflict("qa_bank", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    private fun cursorToJob(c: Cursor): JobRecord {
        fun str(col: String) = c.getString(c.getColumnIndexOrThrow(col)) ?: ""
        fun optStr(col: String): String? {
            val idx = c.getColumnIndexOrThrow(col)
            return if (c.isNull(idx)) null else c.getString(idx)
        }
        fun optInt(col: String): Int? {
            val idx = c.getColumnIndexOrThrow(col)
            return if (c.isNull(idx)) null else c.getInt(idx)
        }
        fun strList(col: String): List<String> =
            runCatching { json.decodeFromString<List<String>>(str(col)) }.getOrDefault(emptyList())

        val breakdown = optStr("score_breakdown_json")?.takeIf { it.isNotBlank() }?.let {
            runCatching { json.decodeFromString<ScoreBreakdown>(it) }.getOrNull()
        }
        val fit = optStr("llm_fit_json")?.takeIf { it.isNotBlank() }?.let {
            runCatching { json.decodeFromString<LLMFitAnalysis>(it) }.getOrNull()
        }
        val pkg = optStr("tailored_package_json")?.takeIf { it.isNotBlank() }?.let {
            runCatching { json.decodeFromString<TailoredApplicationPayload>(it) }.getOrNull()
        }

        return JobRecord(
            id = c.getLong(c.getColumnIndexOrThrow("id")),
            source = str("source"),
            externalId = str("external_id"),
            dedupKey = str("dedup_key"),
            url = str("url"),
            title = str("title"),
            company = str("company"),
            advertiserId = optStr("advertiser_id"),
            isAgency = c.getInt(c.getColumnIndexOrThrow("is_agency")) == 1,
            location = str("location"),
            workType = str("work_type"),
            workArrangement = str("work_arrangement"),
            classifications = strList("classifications_json"),
            teaser = str("teaser"),
            bulletPoints = strList("bullet_points_json"),
            descriptionHtml = str("description_html"),
            descriptionText = str("description_text"),
            salaryLabel = str("salary_label"),
            salaryEstMin = optInt("salary_est_min"),
            salaryEstMax = optInt("salary_est_max"),
            postedAt = optStr("posted_at"),
            expiresAt = optStr("expires_at"),
            isLinkOut = c.getInt(c.getColumnIndexOrThrow("is_link_out")) == 1,
            isExpired = c.getInt(c.getColumnIndexOrThrow("is_expired")) == 1,
            screeningQuestions = strList("screening_questions_json"),
            matchedQueries = strList("matched_queries_json"),
            score = c.getInt(c.getColumnIndexOrThrow("score")),
            scoreBreakdown = breakdown,
            blockedReason = optStr("blocked_reason"),
            status = JobStatus.fromValue(str("status")),
            statusUpdatedAt = optStr("status_updated_at") ?: "",
            appliedAt = optStr("applied_at")?.takeIf { it.isNotBlank() },
            syncedFromDesktop = (optInt("synced_from_desktop") ?: 0) == 1,
            userNotes = str("user_notes"),
            firstSeenAt = str("first_seen_at"),
            lastSeenAt = str("last_seen_at"),
            llmFit = fit,
            llmFitScore = optInt("llm_fit_score"),
            tailoredPackage = pkg,
            tailoredModelUsed = optStr("tailored_model_used")?.takeIf { it.isNotBlank() },
            cvPdfPath = optStr("cv_pdf_path")?.takeIf { it.isNotBlank() },
            coverLetterPdfPath = optStr("cover_letter_pdf_path")?.takeIf { it.isNotBlank() }
        )
    }
}
