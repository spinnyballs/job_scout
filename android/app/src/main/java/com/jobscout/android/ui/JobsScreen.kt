package com.jobscout.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddLink
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.PostAdd
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jobscout.android.model.JobRecord
import com.jobscout.android.model.JobStatus
import com.jobscout.android.model.ManualPastePrefill
import com.jobscout.android.model.ScanProgress

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JobsScreen(
    jobs: List<JobRecord>,
    scanProgress: ScanProgress,
    searchText: String,
    statusFilter: String?,
    tailorQueue: Set<Long>,
    activeTailorId: Long?,
    pendingPastePrefill: ManualPastePrefill? = null,
    onConsumePastePrefill: () -> Unit = {},
    onSearchChange: (String) -> Unit,
    onStatusFilterChange: (String?) -> Unit,
    onRunScan: () -> Unit,
    onImportUrl: (url: String, probeSalary: Boolean, andTailor: Boolean, extraAngle: String) -> Unit,
    onImportPastedJob: (
        descriptionText: String,
        title: String,
        company: String,
        location: String,
        workType: String,
        salaryLabel: String,
        url: String,
        screeningQuestionsRaw: String,
        useAiExtract: Boolean,
        andTailor: Boolean,
        extraAngle: String
    ) -> Unit,
    onSelectJob: (JobRecord) -> Unit,
    onSwipeStatus: (JobRecord, JobStatus) -> Unit,
    onEnqueueTailor: (Long) -> Unit,
    onResetTailored: (Long) -> Unit
) {
    var showImportDialog by remember { mutableStateOf(false) }
    var importModeTab by remember { mutableIntStateOf(0) } // 0 = SEEK Link, 1 = Paste JD
    var urlText by remember { mutableStateOf("") }
    var seekProbeSalary by remember { mutableStateOf(true) }
    var pasteJdText by remember { mutableStateOf("") }
    var pasteTitle by remember { mutableStateOf("") }
    var pasteCompany by remember { mutableStateOf("") }
    var pasteLocation by remember { mutableStateOf("") }
    var pasteSalary by remember { mutableStateOf("") }
    var pasteWorkType by remember { mutableStateOf("") }
    var pasteUrl by remember { mutableStateOf("") }
    var pasteQuestions by remember { mutableStateOf("") }
    var pasteUseAi by remember { mutableStateOf(true) }
    var importExtraAngle by remember { mutableStateOf("") }
    var showMorePasteFields by remember { mutableStateOf(false) }
    var pendingResetJob by remember { mutableStateOf<JobRecord?>(null) }

    LaunchedEffect(pendingPastePrefill) {
        pendingPastePrefill?.let { prefill ->
            importModeTab = 1
            pasteJdText = prefill.jdText
            pasteUrl = prefill.url
            pasteTitle = ""
            pasteCompany = ""
            pasteLocation = ""
            pasteSalary = ""
            pasteWorkType = ""
            pasteQuestions = ""
            importExtraAngle = ""
            showMorePasteFields = prefill.url.isNotBlank()
            showImportDialog = true
            onConsumePastePrefill()
        }
    }

    fun openImportModal(mode: Int) {
        importModeTab = mode
        if (mode == 0) {
            urlText = ""
        } else {
            pasteJdText = ""
            pasteTitle = ""
            pasteCompany = ""
            pasteLocation = ""
            pasteSalary = ""
            pasteWorkType = ""
            pasteUrl = ""
            pasteQuestions = ""
            showMorePasteFields = false
        }
        importExtraAngle = ""
        showImportDialog = true
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Search + Actions Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            OutlinedTextField(
                value = searchText,
                onValueChange = onSearchChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Search...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true
            )
            FilledTonalIconButton(onClick = { openImportModal(0) }) {
                Icon(Icons.Default.AddLink, contentDescription = "Import SEEK URL")
            }
            FilledTonalIconButton(onClick = { openImportModal(1) }) {
                Icon(Icons.Default.PostAdd, contentDescription = "Paste JD (Other Sites)")
            }
            Button(
                onClick = onRunScan,
                enabled = !scanProgress.isRunning,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(if (scanProgress.isRunning) "..." else "Scan")
            }
        }

        // Live Scan Progress
        if (scanProgress.isRunning || scanProgress.stage == "done" || scanProgress.stage == "error") {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Text(
                        text = scanProgress.message,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium
                    )
                    if (scanProgress.isRunning) {
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }

        // Status Filter Chips
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val filters = listOf(
                null to "All (${jobs.size})",
                "discovered" to "New",
                "shortlisted" to "Shortlisted",
                "tailored" to "Tailored",
                "applied" to "Applied",
                "interview" to "Interview",
                "no_reply,rejected" to "No Reply / Closed",
                "skipped" to "Skipped",
                "blocked" to "Blocked"
            )
            filters.forEach { (key, label) ->
                FilterChip(
                    selected = statusFilter == key,
                    onClick = { onStatusFilterChange(key) },
                    label = { Text(label) }
                )
            }
        }

        if (jobs.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                    Text("No jobs in this view", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Tap Scan to fetch SEEK NZ roles, the link icon for a SEEK URL, or the paste icon for LinkedIn / Indeed / company JDs.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(jobs, key = { "${statusFilter ?: "all"}_${it.id}" }) { job ->
                    SwipeableJobItem(
                        job = job,
                        statusFilter = statusFilter,
                        isActiveTailoring = job.id == activeTailorId,
                        isQueuedTailoring = job.id in tailorQueue && job.id != activeTailorId,
                        onClick = { onSelectJob(job) },
                        onSwipeStatus = { newStatus -> onSwipeStatus(job, newStatus) },
                        onEnqueueTailor = { onEnqueueTailor(job.id) },
                        onRequestResetTailored = { pendingResetJob = job }
                    )
                }
            }
        }
    }

    if (showImportDialog) {
        val canSubmit = if (importModeTab == 0) urlText.isNotBlank() else pasteJdText.trim().length >= 20

        fun submitImport(andTailor: Boolean) {
            if (!canSubmit) return
            if (importModeTab == 0) {
                onImportUrl(urlText, seekProbeSalary, andTailor, importExtraAngle)
            } else {
                onImportPastedJob(
                    pasteJdText,
                    pasteTitle,
                    pasteCompany,
                    pasteLocation,
                    pasteWorkType,
                    pasteSalary,
                    pasteUrl,
                    pasteQuestions,
                    pasteUseAi,
                    andTailor,
                    importExtraAngle
                )
            }
            showImportDialog = false
        }

        AlertDialog(
            onDismissRequest = { showImportDialog = false },
            title = { Text("Add Job to Job Scout") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 460.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TabRow(selectedTabIndex = importModeTab) {
                        Tab(
                            selected = importModeTab == 0,
                            onClick = { importModeTab = 0 },
                            text = { Text("SEEK Link") }
                        )
                        Tab(
                            selected = importModeTab == 1,
                            onClick = { importModeTab = 1 },
                            text = { Text("Paste JD (Other)") }
                        )
                    }

                    if (importModeTab == 0) {
                        Text(
                            "Paste any SEEK NZ/AU job URL or numeric ID (or Share directly from SEEK/Chrome).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedTextField(
                            value = urlText,
                            onValueChange = { urlText = it },
                            label = { Text("SEEK Job URL or ID") },
                            placeholder = { Text("https://www.seek.co.nz/job/...") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable { seekProbeSalary = !seekProbeSalary }
                        ) {
                            Checkbox(checked = seekProbeSalary, onCheckedChange = { seekProbeSalary = it })
                            Text("Estimate hidden SEEK salary band", style = MaterialTheme.typography.bodySmall)
                        }
                    } else {
                        Text(
                            "Paste any JD from LinkedIn, Indeed, Trade Me, or company sites. Blank Title/Company/Location/Salary will be auto-extracted.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedTextField(
                            value = pasteJdText,
                            onValueChange = { pasteJdText = it },
                            label = { Text("Job Description *") },
                            placeholder = { Text("Paste the full job ad / description here...") },
                            minLines = 4,
                            maxLines = 8,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = pasteTitle,
                                onValueChange = { pasteTitle = it },
                                label = { Text("Title (opt.)") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = pasteCompany,
                                onValueChange = { pasteCompany = it },
                                label = { Text("Company (opt.)") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        TextButton(
                            onClick = { showMorePasteFields = !showMorePasteFields },
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                        ) {
                            Text(
                                if (showMorePasteFields) "Hide optional fields ▲"
                                else "Location, Salary, URL & Questions ▼"
                            )
                        }
                        if (showMorePasteFields) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = pasteLocation,
                                    onValueChange = { pasteLocation = it },
                                    label = { Text("Location") },
                                    placeholder = { Text("Auckland") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                                OutlinedTextField(
                                    value = pasteSalary,
                                    onValueChange = { pasteSalary = it },
                                    label = { Text("Salary") },
                                    placeholder = { Text("$90k-$110k") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            OutlinedTextField(
                                value = pasteWorkType,
                                onValueChange = { pasteWorkType = it },
                                label = { Text("Work Type (opt.)") },
                                placeholder = { Text("Full time") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = pasteUrl,
                                onValueChange = { pasteUrl = it },
                                label = { Text("Posting URL (opt.)") },
                                placeholder = { Text("https://www.linkedin.com/jobs/...") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = pasteQuestions,
                                onValueChange = { pasteQuestions = it },
                                label = { Text("Screening Questions (one/line)") },
                                minLines = 2,
                                maxLines = 4,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable { pasteUseAi = !pasteUseAi }
                        ) {
                            Checkbox(checked = pasteUseAi, onCheckedChange = { pasteUseAi = it })
                            Text(
                                "Auto-extract missing fields with AI (parser fallback)",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }

                    OutlinedTextField(
                        value = importExtraAngle,
                        onValueChange = { importExtraAngle = it },
                        label = { Text("Tailoring Positioning Hint (opt.)") },
                        placeholder = { Text("e.g., Emphasize PCB bring-up & embedded C") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(
                        onClick = { submitImport(false) },
                        enabled = canSubmit
                    ) { Text("Import") }
                    Button(
                        onClick = { submitImport(true) },
                        enabled = canSubmit
                    ) { Text("Import & Tailor") }
                }
            },
            dismissButton = {
                TextButton(onClick = { showImportDialog = false }) { Text("Cancel") }
            }
        )
    }

    pendingResetJob?.let { targetJob ->
        AlertDialog(
            onDismissRequest = { pendingResetJob = null },
            title = { Text("Reset Tailored Package?") },
            text = {
                Text(
                    "Move \"${targetJob.title}\" back to Shortlisted and delete all generated CV & Cover Letter PDFs?"
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onResetTailored(targetJob.id)
                        pendingResetJob = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Reset & Delete PDFs") }
            },
            dismissButton = {
                TextButton(onClick = { pendingResetJob = null }) { Text("Cancel") }
            }
        )
    }
}

private data class SwipeSpec(
    val label: String,
    val icon: ImageVector,
    val color: Color
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableJobItem(
    job: JobRecord,
    statusFilter: String?,
    isActiveTailoring: Boolean,
    isQueuedTailoring: Boolean,
    onClick: () -> Unit,
    onSwipeStatus: (JobStatus) -> Unit,
    onEnqueueTailor: () -> Unit,
    onRequestResetTailored: () -> Unit
) {
    val startSpec: SwipeSpec?
    val endSpec: SwipeSpec?

    when (statusFilter) {
        null, "discovered" -> {
            startSpec = SwipeSpec("Shortlist", Icons.Default.Star, Color(0xFF15803D))
            endSpec = SwipeSpec("Skip", Icons.Default.Close, Color(0xFFDC2626))
        }
        "shortlisted" -> {
            startSpec = SwipeSpec("Queue Tailor", Icons.Default.AutoAwesome, Color(0xFF1D4ED8))
            endSpec = SwipeSpec("Skip", Icons.Default.Close, Color(0xFFDC2626))
        }
        "tailored" -> {
            startSpec = SwipeSpec("Applied", Icons.Default.DoneAll, Color(0xFF15803D))
            endSpec = SwipeSpec("Reset PDFs", Icons.Default.DeleteSweep, Color(0xFFD97706))
        }
        "applied" -> {
            startSpec = SwipeSpec("Interview", Icons.Default.Check, Color(0xFF1D4ED8))
            endSpec = SwipeSpec("No Reply", Icons.Default.Close, Color(0xFF64748B))
        }
        "interview" -> {
            startSpec = null
            endSpec = SwipeSpec("Rejected", Icons.Default.Close, Color(0xFFDC2626))
        }
        else -> {
            startSpec = null
            endSpec = null
        }
    }

    if (startSpec == null && endSpec == null) {
        JobCard(
            job = job,
            isActiveTailoring = isActiveTailoring,
            isQueuedTailoring = isQueuedTailoring,
            onClick = onClick
        )
        return
    }

    val currentFilter by rememberUpdatedState(statusFilter)
    val currentOnSwipeStatus by rememberUpdatedState(onSwipeStatus)
    val currentOnEnqueueTailor by rememberUpdatedState(onEnqueueTailor)
    val currentOnRequestResetTailored by rememberUpdatedState(onRequestResetTailored)

    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> {
                    when (currentFilter) {
                        null, "discovered" -> currentOnSwipeStatus(JobStatus.SHORTLISTED)
                        "shortlisted" -> currentOnEnqueueTailor()
                        "tailored" -> currentOnSwipeStatus(JobStatus.APPLIED)
                        "applied" -> currentOnSwipeStatus(JobStatus.INTERVIEW)
                    }
                    false
                }
                SwipeToDismissBoxValue.EndToStart -> {
                    when (currentFilter) {
                        null, "discovered", "shortlisted" -> currentOnSwipeStatus(JobStatus.SKIPPED)
                        "tailored" -> currentOnRequestResetTailored()
                        "applied" -> currentOnSwipeStatus(JobStatus.NO_REPLY)
                        "interview" -> currentOnSwipeStatus(JobStatus.REJECTED)
                    }
                    false
                }
                SwipeToDismissBoxValue.Settled -> false
            }
        }
    )

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = startSpec != null,
        enableDismissFromEndToStart = endSpec != null,
        backgroundContent = {
            val direction = dismissState.dismissDirection
            val spec = when (direction) {
                SwipeToDismissBoxValue.StartToEnd -> startSpec
                SwipeToDismissBoxValue.EndToStart -> endSpec
                SwipeToDismissBoxValue.Settled -> null
            }
            val bgColor = spec?.color ?: Color.Transparent
            val alignment = if (direction == SwipeToDismissBoxValue.StartToEnd) {
                Alignment.CenterStart
            } else {
                Alignment.CenterEnd
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CardDefaults.shape)
                    .background(bgColor.copy(alpha = 0.88f))
                    .padding(horizontal = 20.dp),
                contentAlignment = alignment
            ) {
                if (spec != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (direction == SwipeToDismissBoxValue.StartToEnd) {
                            Icon(spec.icon, contentDescription = null, tint = Color.White)
                            Text(spec.label, color = Color.White, fontWeight = FontWeight.Bold)
                        } else {
                            Text(spec.label, color = Color.White, fontWeight = FontWeight.Bold)
                            Icon(spec.icon, contentDescription = null, tint = Color.White)
                        }
                    }
                }
            }
        }
    ) {
        JobCard(
            job = job,
            isActiveTailoring = isActiveTailoring,
            isQueuedTailoring = isQueuedTailoring,
            onClick = onClick
        )
    }
}

@Composable
private fun JobCard(
    job: JobRecord,
    isActiveTailoring: Boolean,
    isQueuedTailoring: Boolean,
    onClick: () -> Unit
) {
    val scoreColor = when {
        job.score >= 70 -> Color(0xFF15803D)
        job.score >= 50 -> Color(0xFF1D4ED8)
        else -> Color(0xFF64748B)
    }
    val salaryText = job.salaryLabel.ifBlank {
        if (job.salaryEstMin != null) "~$${job.salaryEstMin / 1000}k–$${(job.salaryEstMax ?: job.salaryEstMin) / 1000}k"
        else if (job.source == "manual") "Salary unlisted"
        else "Salary unprobed"
    }
    val subLine = listOfNotNull(
        job.company.takeIf { it.isNotBlank() },
        job.location.takeIf { it.isNotBlank() }
    ).joinToString(" · ").ifBlank { "Details pending" }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = job.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(end = 8.dp)
                )
                Surface(
                    color = scoreColor.copy(alpha = 0.14f),
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(
                        text = "${job.score}",
                        color = scoreColor,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = subLine,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = salaryText,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (job.isExpired) {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = MaterialTheme.shapes.extraSmall
                        ) {
                            Text(
                                text = "EXPIRED",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    if (job.syncedFromDesktop) {
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = MaterialTheme.shapes.extraSmall
                        ) {
                            Text(
                                text = "FROM PC",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    if (job.source == "manual") {
                        Surface(
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                            shape = MaterialTheme.shapes.extraSmall
                        ) {
                            Text(
                                text = "PASTED JD",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    if (isActiveTailoring) {
                        AssistChip(
                            onClick = onClick,
                            leadingIcon = {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp
                                )
                            },
                            label = { Text("Tailoring...") }
                        )
                    } else if (isQueuedTailoring) {
                        AssistChip(
                            onClick = onClick,
                            leadingIcon = {
                                Icon(
                                    Icons.Default.AutoAwesome,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp)
                                )
                            },
                            label = { Text("Queued") }
                        )
                    } else if (job.cvPdfPath != null) {
                        AssistChip(
                            onClick = onClick,
                            leadingIcon = {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp)
                                )
                            },
                            label = { Text("PDF Ready") }
                        )
                    }
                    Text(
                        text = job.status.displayLabel().uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }
        }
    }
}

