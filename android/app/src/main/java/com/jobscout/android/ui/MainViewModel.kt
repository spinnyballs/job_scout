package com.jobscout.android.ui

import android.app.Application
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jobscout.android.data.ConfigRepository
import com.jobscout.android.data.JobDatabase
import com.jobscout.android.domain.ScoringEngine
import com.jobscout.android.domain.TailorService
import com.jobscout.android.model.CandidateProfile
import com.jobscout.android.model.CustomProviderPreset
import com.jobscout.android.model.JobRecord
import com.jobscout.android.model.JobStatus
import com.jobscout.android.model.LLMProviderConfig
import com.jobscout.android.model.NormalizedJob
import com.jobscout.android.model.ProfileAuditSuggestions
import com.jobscout.android.model.QABankEntry
import com.jobscout.android.model.ScanProgress
import com.jobscout.android.model.ScannerConfig
import com.jobscout.android.model.SearchQueryConfig
import com.jobscout.android.network.LlmProviderRegistry
import com.jobscout.android.network.SeekClient
import com.jobscout.android.network.SyncClient
import com.jobscout.android.pdf.WebViewPdfRenderer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class MainViewModel(application: Application) : AndroidViewModel(application) {

    val configRepo = ConfigRepository(application)
    val db = JobDatabase(application) { configRepo.loadDefaultQaEntries() }

    private val _jobs = MutableStateFlow<List<JobRecord>>(emptyList())
    val jobs: StateFlow<List<JobRecord>> = _jobs.asStateFlow()

    private val _selectedJob = MutableStateFlow<JobRecord?>(null)
    val selectedJob: StateFlow<JobRecord?> = _selectedJob.asStateFlow()

    private val _scanProgress = MutableStateFlow(ScanProgress())
    val scanProgress: StateFlow<ScanProgress> = _scanProgress.asStateFlow()

    private val _config = MutableStateFlow(configRepo.loadConfig())
    val config: StateFlow<ScannerConfig> = _config.asStateFlow()

    private val _profile = MutableStateFlow(configRepo.loadProfile())
    val profile: StateFlow<CandidateProfile> = _profile.asStateFlow()

    private val _qaEntries = MutableStateFlow<List<QABankEntry>>(emptyList())
    val qaEntries: StateFlow<List<QABankEntry>> = _qaEntries.asStateFlow()

    private val _searchText = MutableStateFlow("")
    val searchText: StateFlow<String> = _searchText.asStateFlow()

    private val _statusFilter = MutableStateFlow<String?>(null)
    val statusFilter: StateFlow<String?> = _statusFilter.asStateFlow()

    private val _busyMessage = MutableStateFlow<String?>(null)
    val busyMessage: StateFlow<String?> = _busyMessage.asStateFlow()

    private val _toastMessage = MutableStateFlow<String?>(null)
    val toastMessage: StateFlow<String?> = _toastMessage.asStateFlow()

    private val _profileAudit = MutableStateFlow<ProfileAuditSuggestions?>(null)
    val profileAudit: StateFlow<ProfileAuditSuggestions?> = _profileAudit.asStateFlow()

    private val _desktopSyncUrl = MutableStateFlow(configRepo.getDesktopSyncUrl())
    val desktopSyncUrl: StateFlow<String> = _desktopSyncUrl.asStateFlow()

    private val _lastSyncSummary = MutableStateFlow<String?>(null)
    val lastSyncSummary: StateFlow<String?> = _lastSyncSummary.asStateFlow()

    private val _tailorQueue = MutableStateFlow<Set<Long>>(emptySet())
    val tailorQueue: StateFlow<Set<Long>> = _tailorQueue.asStateFlow()

    private val _activeTailorId = MutableStateFlow<Long?>(null)
    val activeTailorId: StateFlow<Long?> = _activeTailorId.asStateFlow()

    private val _undoLabel = MutableStateFlow<String?>(null)
    val undoLabel: StateFlow<String?> = _undoLabel.asStateFlow()
    private var pendingUndo: (() -> Unit)? = null

    private val pendingAngles = mutableMapOf<Long, String>()
    private val tailorChannel = kotlinx.coroutines.channels.Channel<Long>(kotlinx.coroutines.channels.Channel.UNLIMITED)
    private val scanLock = AtomicBoolean(false)

    init {
        refreshJobs()
        refreshQa()
        viewModelScope.launch {
            for (jobId in tailorChannel) {
                _activeTailorId.value = jobId
                runCatching { performTailor(jobId, pendingAngles.remove(jobId) ?: "") }
                _tailorQueue.value = _tailorQueue.value - jobId
                _activeTailorId.value = null
            }
        }
    }

    fun clearToast() {
        _toastMessage.value = null
    }

    fun setSearchText(text: String) {
        _searchText.value = text
        refreshJobs()
    }

    fun setStatusFilter(status: String?) {
        _statusFilter.value = status
        refreshJobs()
    }

    fun refreshJobs() {
        _jobs.value = db.listJobs(
            search = _searchText.value,
            statusFilter = _statusFilter.value
        )
        _selectedJob.value?.let { sel ->
            _selectedJob.value = db.getJob(sel.id)
        }
    }

    fun selectJob(job: JobRecord?) {
        _selectedJob.value = job?.let { db.getJob(it.id) ?: it }
    }

    fun refreshQa() {
        _qaEntries.value = db.listQaEntries()
    }

    fun runScan(maxAutoSalaryProbes: Int = 6) {
        if (!scanLock.compareAndSet(false, true)) return
        viewModelScope.launch {
            val cfg = _config.value
            val client = SeekClient(cfg.requestDelayMs)
            val enabledQueries = cfg.queries.filter { it.enabled }
            val errors = mutableListOf<String>()

            _scanProgress.value = ScanProgress(
                isRunning = true,
                stage = "searching",
                message = "Scanning ${enabledQueries.size} search profile(s)...",
                queriesTotal = enabledQueries.size
            )

            try {
                val batch = linkedMapOf<String, NormalizedJob>()
                var fetched = 0
                enabledQueries.forEachIndexed { idx, q ->
                    _scanProgress.value = _scanProgress.value.copy(
                        message = "[${idx + 1}/${enabledQueries.size}] ${q.name}..."
                    )
                    try {
                        val items = client.search(cfg.defaultBoard, q)
                        for (item in items) {
                            fetched++
                            val existing = batch[item.externalId]
                            if (existing != null) {
                                q.name.let { name ->
                                    if (name !in existing.matchedQueries) existing.matchedQueries.add(name)
                                }
                            } else {
                                batch[item.externalId] = item
                            }
                        }
                    } catch (e: Exception) {
                        errors.add("${q.name}: ${e.message}")
                    }
                    _scanProgress.value = _scanProgress.value.copy(
                        queriesDone = idx + 1,
                        fetched = fetched,
                        errors = errors.toList()
                    )
                }

                _scanProgress.value = _scanProgress.value.copy(stage = "enriching")
                val itemsList = batch.values.toList()
                val probeCandidates = mutableListOf<Triple<Long, NormalizedJob, Int>>()
                var newCount = 0
                var detailsDone = 0

                itemsList.forEachIndexed { i, job ->
                    val existing = db.getExistingRaw(job.source, job.externalId)
                    val preScore = ScoringEngine.scoreJob(job, cfg)
                    val needsDetail = cfg.fetchDetailsForAllNew &&
                        (existing == null || existing.descriptionText.isBlank())

                    if (needsDetail && preScore.blockedReason == null) {
                        _scanProgress.value = _scanProgress.value.copy(
                            message = "Fetching JD (${i + 1}/${itemsList.size}): ${job.title.take(32)}..."
                        )
                        runCatching { client.enrichDetail(cfg.defaultBoard, job) }
                            .onSuccess { detailsDone++ }
                            .onFailure { errors.add("JD ${job.externalId}: ${it.message}") }
                    } else if (existing != null && existing.descriptionText.isNotBlank()) {
                        job.descriptionHtml = existing.descriptionHtml
                        job.descriptionText = existing.descriptionText
                        job.isLinkOut = existing.isLinkOut
                        if (job.salaryEstMin == null) {
                            job.salaryEstMin = existing.salaryEstMin
                            job.salaryEstMax = existing.salaryEstMax
                        }
                    }

                    val finalScore = ScoringEngine.scoreJob(job, cfg)
                    val (rowId, isNew) = db.upsertJob(job, finalScore)
                    if (isNew) newCount++

                    if (finalScore.blockedReason == null && job.salaryEstMin == null &&
                        finalScore.score >= cfg.probeSalaryAboveScore
                    ) {
                        probeCandidates.add(Triple(rowId, job, finalScore.score))
                    }

                    _scanProgress.value = _scanProgress.value.copy(
                        newJobs = newCount,
                        detailsDone = detailsDone,
                        errors = errors.toList()
                    )
                }

                if (probeCandidates.isNotEmpty() && maxAutoSalaryProbes > 0) {
                    _scanProgress.value = _scanProgress.value.copy(stage = "probing_salary")
                    val top = probeCandidates.sortedByDescending { it.third }.take(maxAutoSalaryProbes)
                    var salDone = 0
                    top.forEachIndexed { idx, (rowId, job, _) ->
                        _scanProgress.value = _scanProgress.value.copy(
                            message = "Probing salary (${idx + 1}/${top.size}): ${job.title.take(30)}..."
                        )
                        runCatching { client.probeSalary(cfg.defaultBoard, job) }
                            .onSuccess { (smin, smax) ->
                                if (smin != null) {
                                    job.salaryEstMin = smin
                                    job.salaryEstMax = smax
                                    val bd = ScoringEngine.scoreJob(job, cfg)
                                    db.updateSalary(rowId, smin, smax, bd)
                                    salDone++
                                }
                            }
                        _scanProgress.value = _scanProgress.value.copy(salariesDone = salDone)
                    }
                }

                refreshJobs()
                _scanProgress.value = _scanProgress.value.copy(
                    isRunning = false,
                    stage = "done",
                    message = "Done: $newCount new jobs ($fetched scanned)"
                )
            } catch (e: Exception) {
                _scanProgress.value = _scanProgress.value.copy(
                    isRunning = false,
                    stage = "error",
                    message = "Scan failed: ${e.message}"
                )
            } finally {
                scanLock.set(false)
            }
        }
    }

    fun importJobUrl(rawUrl: String, probeSalary: Boolean = true, onImported: ((JobRecord) -> Unit)? = null) {
        viewModelScope.launch {
            _busyMessage.value = "Importing SEEK job..."
            try {
                val cfg = _config.value
                val (boardId, extId) = SeekClient.parseSeekUrlOrId(rawUrl, cfg.defaultBoard)
                val client = SeekClient(cfg.requestDelayMs)
                val prefix = SeekClient.BOARDS[boardId]?.jobUrlPrefix ?: "https://nz.seek.com/job"
                val stub = NormalizedJob(
                    source = boardId,
                    externalId = extId,
                    url = "$prefix/$extId",
                    title = "",
                    company = "",
                    matchedQueries = mutableListOf("Manual Link")
                )
                val enriched = client.enrichDetail(boardId, stub)
                if (probeSalary && enriched.salaryEstMin == null) {
                    runCatching { client.probeSalary(boardId, enriched) }.onSuccess { (smin, smax) ->
                        enriched.salaryEstMin = smin
                        enriched.salaryEstMax = smax
                    }
                }
                val breakdown = ScoringEngine.scoreJob(enriched, cfg)
                val (rowId, _) = db.upsertJob(enriched, breakdown)
                refreshJobs()
                val saved = db.getJob(rowId)
                if (saved != null) {
                    _selectedJob.value = saved
                    onImported?.invoke(saved)
                }
                _toastMessage.value = "Imported: ${enriched.title}"
            } catch (e: Exception) {
                _toastMessage.value = "Import failed: ${e.message}"
            } finally {
                _busyMessage.value = null
            }
        }
    }

    fun probeSalary(jobId: Long) {
        viewModelScope.launch {
            val rec = db.getJob(jobId) ?: return@launch
            _busyMessage.value = "Estimating hidden SEEK salary..."
            try {
                val cfg = _config.value
                val client = SeekClient(cfg.requestDelayMs)
                val norm = recordToNormalized(rec)
                val (smin, smax) = client.probeSalary(rec.source, norm)
                norm.salaryEstMin = smin
                norm.salaryEstMax = smax
                val bd = ScoringEngine.scoreJob(norm, cfg)
                db.updateSalary(jobId, smin, smax, bd)
                refreshJobs()
                _toastMessage.value = if (smin != null) {
                    "Estimated: $${smin / 1000}k – $${(smax ?: smin) / 1000}k"
                } else {
                    "Could not isolate hidden salary band."
                }
            } catch (e: Exception) {
                _toastMessage.value = "Salary probe failed: ${e.message}"
            } finally {
                _busyMessage.value = null
            }
        }
    }

    fun analyzeFit(jobId: Long) {
        viewModelScope.launch {
            val rec = db.getJob(jobId) ?: return@launch
            _busyMessage.value = "Running AI Fit Analysis..."
            try {
                val provider = LlmProviderRegistry.create(_config.value.llm, configRepo)
                val fit = TailorService.analyzeFit(provider, _profile.value, rec)
                db.saveLlmFit(jobId, fit)
                refreshJobs()
                _toastMessage.value = "Fit: ${fit.fitScore}/100 (${fit.verdict})"
            } catch (e: Exception) {
                _toastMessage.value = "Fit analysis failed: ${e.message}"
            } finally {
                _busyMessage.value = null
            }
        }
    }

    fun enqueueTailor(jobId: Long, extraAngle: String = "") {
        if (jobId in _tailorQueue.value) {
            _toastMessage.value = "Already queued for AI tailoring"
            return
        }
        if (extraAngle.isNotBlank()) pendingAngles[jobId] = extraAngle
        _tailorQueue.value = _tailorQueue.value + jobId
        tailorChannel.trySend(jobId)
        _toastMessage.value = "Queued for AI tailoring"
    }

    fun tailorAndRenderPdf(jobId: Long, extraAngle: String = "") {
        enqueueTailor(jobId, extraAngle)
    }

    private suspend fun performTailor(jobId: Long, extraAngle: String) {
        val rec = db.getJob(jobId) ?: return
        try {
            val provider = LlmProviderRegistry.create(_config.value.llm, configRepo)
            val profile = _profile.value
            val qa = db.listQaEntries()
            val payload = TailorService.generateTailoredPackage(provider, profile, rec, qa, extraAngle)

            val folderName = "job_${rec.id}_${TailorService.slugify(rec.company)}_${TailorService.slugify(rec.title)}"
            val outDir = File(getApplication<Application>().filesDir, "artifacts/$folderName")
            outDir.mkdirs()

            val baseName = "${TailorService.slugify(profile.fullName)}_${TailorService.slugify(rec.company)}_${TailorService.slugify(rec.title)}"
            val cvFileName = "${baseName}_CV.pdf"
            val clFileName = "${baseName}_Cover_Letter.pdf"

            val cvHtml = TailorService.renderCvHtml(profile, payload)
            val clHtml = TailorService.renderCoverLetterHtml(profile, payload, rec.title, rec.company)

            val cvPdf = File(outDir, cvFileName)
            val clPdf = File(outDir, clFileName)

            WebViewPdfRenderer.renderHtmlToPdf(getApplication(), cvHtml, cvPdf)
            WebViewPdfRenderer.renderHtmlToPdf(getApplication(), clHtml, clPdf)

            WebViewPdfRenderer.copyToDownloads(getApplication(), cvPdf, cvFileName)
            WebViewPdfRenderer.copyToDownloads(getApplication(), clPdf, clFileName)

            db.saveTailoredPackage(
                jobId = jobId,
                payload = payload,
                modelUsed = provider.currentModelName(),
                cvPdfPath = cvPdf.absolutePath,
                clPdfPath = clPdf.absolutePath
            )
            refreshJobs()
            _toastMessage.value = "Tailored PDFs saved to Downloads/JobScout: ${rec.title}"
        } catch (e: Exception) {
            _toastMessage.value = "Tailoring failed (${rec.title.take(22)}): ${e.message}"
        }
    }

    fun resetTailoredToShortlisted(jobId: Long) {
        val rec = db.getJob(jobId) ?: return
        listOfNotNull(rec.cvPdfPath, rec.coverLetterPdfPath).forEach { path ->
            runCatching { File(path).delete() }
        }
        val folderName = "job_${rec.id}_${TailorService.slugify(rec.company)}_${TailorService.slugify(rec.title)}"
        runCatching { File(getApplication<Application>().filesDir, "artifacts/$folderName").deleteRecursively() }
        db.clearTailoredPackage(jobId)
        refreshJobs()
        _toastMessage.value = "Moved back to Shortlisted & cleared PDFs"
    }

    fun swipeStatusChange(job: JobRecord, newStatus: JobStatus) {
        val prev = job.status
        db.updateStatus(job.id, newStatus)
        refreshJobs()
        pendingUndo = {
            db.updateStatus(job.id, prev)
            refreshJobs()
        }
        _undoLabel.value = "${job.title.take(26)} → ${newStatus.value}"
    }

    fun undoLastSwipe() {
        pendingUndo?.invoke()
        pendingUndo = null
        _undoLabel.value = null
    }

    fun clearUndo() {
        pendingUndo = null
        _undoLabel.value = null
    }

    fun sharePdf(path: String) {
        val ctx = getApplication<Application>()
        val file = File(path)
        if (!file.exists()) {
            _toastMessage.value = "PDF file not found."
            return
        }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/pdf")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { ctx.startActivity(intent) }.onFailure {
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(Intent.createChooser(sendIntent, "Open or Save PDF").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    fun updateStatus(jobId: Long, status: JobStatus) {
        db.updateStatus(jobId, status)
        refreshJobs()
    }

    fun updateLlmConfig(newLlm: LLMProviderConfig) {
        val updated = configRepo.saveConfig(_config.value.copy(llm = newLlm))
        _config.value = updated
        _toastMessage.value = "AI settings saved"
    }

    fun addCustomProviderPreset(label: String, baseUrl: String, model: String, apiKeyName: String, apiKeyValue: String) {
        val cleanId = "custom_${label.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')}"
        val keyEnv = apiKeyName.trim().ifBlank { "${cleanId.uppercase()}_API_KEY" }
        if (apiKeyValue.isNotBlank()) {
            configRepo.setApiKey(keyEnv, apiKeyValue)
        }
        val preset = CustomProviderPreset(
            id = cleanId,
            label = label.trim(),
            baseUrl = baseUrl.trim(),
            model = model.trim(),
            apiKeyName = keyEnv
        )
        val cur = _config.value.llm
        val updatedList = cur.customPresets.filterNot { it.id == cleanId } + preset
        updateLlmConfig(cur.copy(activeProvider = cleanId, customPresets = updatedList))
    }

    fun saveApiKey(keyName: String, value: String) {
        configRepo.setApiKey(keyName, value)
        _toastMessage.value = if (value.isBlank()) "Cleared $keyName" else "Saved $keyName"
    }

    fun toggleQueryEnabled(index: Int) {
        val cur = _config.value
        val updatedQueries = cur.queries.mapIndexed { i, q ->
            if (i == index) q.copy(enabled = !q.enabled) else q
        }
        _config.value = configRepo.saveConfig(cur.copy(queries = updatedQueries))
    }

    fun addSearchQuery(q: SearchQueryConfig) {
        val cur = _config.value
        _config.value = configRepo.saveConfig(cur.copy(queries = cur.queries + q))
        _toastMessage.value = "Added query: ${q.name}"
    }

    fun updateSearchQuery(index: Int, q: SearchQueryConfig) {
        val cur = _config.value
        val updated = cur.queries.mapIndexed { i, old -> if (i == index) q else old }
        _config.value = configRepo.saveConfig(cur.copy(queries = updated))
        _toastMessage.value = "Updated query: ${q.name}"
    }

    fun deleteSearchQuery(index: Int) {
        val cur = _config.value
        val updated = cur.queries.filterIndexed { i, _ -> i != index }
        _config.value = configRepo.saveConfig(cur.copy(queries = updated))
        _toastMessage.value = "Deleted search query"
    }

    fun addBlockedCompany(company: String) {
        val cleaned = company.trim()
        if (cleaned.isEmpty()) return
        val cur = _config.value
        if (cur.blockedCompanies.any { it.equals(cleaned, ignoreCase = true) }) return
        val updated = configRepo.saveConfig(cur.copy(blockedCompanies = cur.blockedCompanies + cleaned))
        _config.value = updated
        db.rescoreAll(updated)
        refreshJobs()
        _toastMessage.value = "Blocked '$cleaned' and re-scored jobs"
    }

    fun removeBlockedCompany(company: String) {
        val cur = _config.value
        val updated = configRepo.saveConfig(
            cur.copy(blockedCompanies = cur.blockedCompanies.filterNot { it == company })
        )
        _config.value = updated
        db.rescoreAll(updated)
        refreshJobs()
        _toastMessage.value = "Removed '$company' from blocklist"
    }

    fun rescoreAllJobs() {
        val count = db.rescoreAll(_config.value)
        refreshJobs()
        _toastMessage.value = "Re-scored $count job(s)"
    }

    fun saveProfile(updated: CandidateProfile) {
        _profile.value = configRepo.saveProfile(updated)
        _toastMessage.value = "Profile saved"
    }

    fun auditProfileWithAi() {
        viewModelScope.launch {
            _busyMessage.value = "Checking profile with AI..."
            try {
                val provider = LlmProviderRegistry.create(_config.value.llm, configRepo)
                val recent = db.listJobs().take(15)
                val suggestions = TailorService.auditProfile(provider, _profile.value, recent)
                _profileAudit.value = suggestions
                _toastMessage.value = "AI profile suggestions ready!"
            } catch (e: Exception) {
                _toastMessage.value = "Profile check failed: ${e.message}"
            } finally {
                _busyMessage.value = null
            }
        }
    }

    fun syncWithDesktop(rawUrl: String, overwriteDesktopProfile: Boolean = true) {
        viewModelScope.launch {
            _busyMessage.value = "Syncing with Desktop over Wi-Fi..."
            try {
                val normalized = SyncClient.normalizeBaseUrl(rawUrl)
                configRepo.setDesktopSyncUrl(normalized)
                _desktopSyncUrl.value = normalized

                val jobsToSync = db.listJobsForSync()
                val res = SyncClient.syncWithDesktop(
                    rawUrl = normalized,
                    profile = _profile.value,
                    overwriteProfile = overwriteDesktopProfile,
                    jobs = jobsToSync
                )
                val phoneMarkedApplied = db.applyDesktopAppliedUpdates(res.allAppliedKeys)
                refreshJobs()

                val msg = buildString {
                    append("Synced ${res.upserted} shortlisted/tailored job(s) to Desktop")
                    if (res.markedAppliedOnDesktop > 0) append(", ${res.markedAppliedOnDesktop} applied → Desktop")
                    if (phoneMarkedApplied > 0) append(", $phoneMarkedApplied marked Applied on Phone")
                    if (overwriteDesktopProfile) append(", Profile synced")
                }
                _lastSyncSummary.value = msg
                _toastMessage.value = msg
            } catch (e: Exception) {
                val err = "Wi-Fi Sync failed: ${e.message}"
                _lastSyncSummary.value = err
                _toastMessage.value = err
            } finally {
                _busyMessage.value = null
            }
        }
    }

    fun pullProfileFromDesktop(rawUrl: String) {
        viewModelScope.launch {
            _busyMessage.value = "Pulling Profile from Desktop..."
            try {
                val normalized = SyncClient.normalizeBaseUrl(rawUrl)
                configRepo.setDesktopSyncUrl(normalized)
                _desktopSyncUrl.value = normalized

                val res = SyncClient.syncWithDesktop(
                    rawUrl = normalized,
                    profile = null,
                    overwriteProfile = false,
                    jobs = emptyList()
                )
                val phoneMarkedApplied = db.applyDesktopAppliedUpdates(res.allAppliedKeys)
                refreshJobs()
                if (res.serverProfile != null) {
                    _profile.value = configRepo.saveProfile(res.serverProfile)
                }
                val msg = "Pulled Desktop profile" + if (phoneMarkedApplied > 0) " & marked $phoneMarkedApplied Applied" else ""
                _lastSyncSummary.value = msg
                _toastMessage.value = msg
            } catch (e: Exception) {
                val err = "Pull failed: ${e.message}"
                _lastSyncSummary.value = err
                _toastMessage.value = err
            } finally {
                _busyMessage.value = null
            }
        }
    }

    fun saveQaEntry(entry: QABankEntry) {
        db.upsertQaEntry(entry)
        refreshQa()
        _toastMessage.value = "Q&A entry saved"
    }

    private fun recordToNormalized(r: JobRecord) = NormalizedJob(
        source = r.source,
        externalId = r.externalId,
        url = r.url,
        title = r.title,
        company = r.company,
        advertiserId = r.advertiserId,
        isAgency = r.isAgency,
        location = r.location,
        workType = r.workType,
        workArrangement = r.workArrangement,
        classifications = r.classifications,
        teaser = r.teaser,
        bulletPoints = r.bulletPoints,
        descriptionHtml = r.descriptionHtml,
        descriptionText = r.descriptionText,
        salaryLabel = r.salaryLabel,
        salaryEstMin = r.salaryEstMin,
        salaryEstMax = r.salaryEstMax,
        postedAt = r.postedAt,
        expiresAt = r.expiresAt,
        isLinkOut = r.isLinkOut,
        isExpired = r.isExpired,
        screeningQuestions = r.screeningQuestions,
        matchedQueries = r.matchedQueries.toMutableList()
    )
}
