package com.jobscout.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddLink
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.DoneAll
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
    onSearchChange: (String) -> Unit,
    onStatusFilterChange: (String?) -> Unit,
    onRunScan: () -> Unit,
    onImportUrl: (String) -> Unit,
    onSelectJob: (JobRecord) -> Unit,
    onSwipeStatus: (JobRecord, JobStatus) -> Unit,
    onEnqueueTailor: (Long) -> Unit,
    onResetTailored: (Long) -> Unit
) {
    var showImportDialog by remember { mutableStateOf(false) }
    var pendingResetJob by remember { mutableStateOf<JobRecord?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        // Search + Actions Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = searchText,
                onValueChange = onSearchChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Search title, company...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true
            )
            FilledTonalIconButton(onClick = { showImportDialog = true }) {
                Icon(Icons.Default.AddLink, contentDescription = "Import SEEK URL")
            }
            Button(
                onClick = onRunScan,
                enabled = !scanProgress.isRunning
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
                "skipped" to "Skipped"
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
                        "Tap Scan to fetch SEEK NZ roles, or tap the link icon to import a SEEK URL.",
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
        var urlText by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showImportDialog = false },
            title = { Text("Import SEEK Job Link") },
            text = {
                Column {
                    Text(
                        "Paste any SEEK NZ/AU job URL or numeric ID (or use Android's Share button from SEEK/Chrome).",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = urlText,
                        onValueChange = { urlText = it },
                        placeholder = { Text("https://www.seek.co.nz/job/...") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (urlText.isNotBlank()) {
                            onImportUrl(urlText)
                            showImportDialog = false
                        }
                    }
                ) { Text("Import") }
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
                    }
                    false
                }
                SwipeToDismissBoxValue.EndToStart -> {
                    when (currentFilter) {
                        null, "discovered", "shortlisted" -> currentOnSwipeStatus(JobStatus.SKIPPED)
                        "tailored" -> currentOnRequestResetTailored()
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
        else "Salary unprobed"
    }

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
                text = "${job.company} · ${job.location}",
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
                        text = job.status.value.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }
        }
    }
}

