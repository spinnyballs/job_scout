package com.jobscout.android.ui

import android.content.Intent
import android.net.Uri
import android.text.method.LinkMovementMethod
import android.widget.TextView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.text.HtmlCompat
import com.jobscout.android.model.JobRecord
import com.jobscout.android.model.JobStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JobDetailScreen(
    job: JobRecord,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onProbeSalary: (Long) -> Unit,
    onAnalyzeFit: (Long) -> Unit,
    onTailorAndPdf: (Long, String) -> Unit,
    onOpenPdf: (String) -> Unit,
    onStatusChange: (Long, JobStatus) -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var extraAngle by remember(job.id) { mutableStateOf("") }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(job.company, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(job.url)))
                    }) {
                        Icon(Icons.Default.OpenInBrowser, contentDescription = "Open on SEEK")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(job.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                "${job.company} · ${job.location} · ${job.workType.ifBlank { "Full time" }}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Score & Salary row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SuggestionChip(onClick = {}, label = { Text("Rule Score: ${job.score}/100") })
                val sal = job.salaryLabel.ifBlank {
                    if (job.salaryEstMin != null) "~$${job.salaryEstMin / 1000}k–$${(job.salaryEstMax ?: job.salaryEstMin) / 1000}k"
                    else "No Salary"
                }
                SuggestionChip(onClick = {}, label = { Text(sal) })
                if (job.salaryEstMin == null) {
                    OutlinedButton(onClick = { onProbeSalary(job.id) }) {
                        Text("Probe Salary")
                    }
                }
            }

            // Status quick selector
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(JobStatus.SHORTLISTED, JobStatus.APPLIED, JobStatus.SKIPPED).forEach { st ->
                    FilterChip(
                        selected = job.status == st,
                        onClick = { onStatusChange(job.id, st) },
                        label = { Text(st.value.replaceFirstChar { it.uppercase() }) }
                    )
                }
            }

            // AI Fit Analysis Card
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("AI Fit Analysis", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        OutlinedButton(onClick = { onAnalyzeFit(job.id) }) {
                            Text(if (job.llmFit == null) "Analyze Fit" else "Re-Analyze")
                        }
                    }
                    job.llmFit?.let { fit ->
                        Text("${fit.fitScore}/100 — ${fit.verdict} (${fit.roleAngle})", fontWeight = FontWeight.SemiBold)
                        Text(fit.rationale, style = MaterialTheme.typography.bodySmall)
                        if (fit.strengths.isNotEmpty()) {
                            Text("Strengths:", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelMedium)
                            fit.strengths.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                        }
                        if (fit.gapsAndRisks.isNotEmpty()) {
                            Text("Gaps / Risks:", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelMedium)
                            fit.gapsAndRisks.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
            }

            // Tailor CV & Cover Letter -> PDF Card
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Tailor CV & Cover Letter (A4 PDF)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    OutlinedTextField(
                        value = extraAngle,
                        onValueChange = { extraAngle = it },
                        label = { Text("Optional emphasis / angle") },
                        placeholder = { Text("e.g., Emphasize PCB rework & calibration") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Button(
                        onClick = { onTailorAndPdf(job.id, extraAngle) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (job.tailoredPackage == null) "Generate Tailored CV & Cover Letter PDF" else "Regenerate Tailored PDFs")
                    }

                    if (job.cvPdfPath != null || job.coverLetterPdfPath != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            job.cvPdfPath?.let { path ->
                                OutlinedButton(
                                    onClick = { onOpenPdf(path) },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.PictureAsPdf, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("CV PDF")
                                }
                            }
                            job.coverLetterPdfPath?.let { path ->
                                OutlinedButton(
                                    onClick = { onOpenPdf(path) },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.PictureAsPdf, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Cover PDF")
                                }
                            }
                        }
                    }

                    job.tailoredPackage?.let { pkg ->
                        HorizontalDivider()
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Cover Letter Text", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                            TextButton(onClick = { clipboard.setText(AnnotatedString(pkg.coverLetterBody)) }) {
                                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Copy")
                            }
                        }
                        Text(pkg.coverLetterBody, style = MaterialTheme.typography.bodySmall)

                        if (pkg.suggestedScreeningAnswers.isNotEmpty()) {
                            HorizontalDivider()
                            Text("Drafted Screening Answers", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                            pkg.suggestedScreeningAnswers.forEach { (q, a) ->
                                Text("Q: $q", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                                Text("A: $a", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }

            // Full Job Description
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Job Description", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    if (job.bulletPoints.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            job.bulletPoints.forEach { bp ->
                                Text("• $bp", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                            }
                        }
                        HorizontalDivider()
                    }
                    if (job.descriptionHtml.isNotBlank()) {
                        val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
                        val linkColor = MaterialTheme.colorScheme.primary.toArgb()
                        AndroidView(
                            modifier = Modifier.fillMaxWidth(),
                            factory = { ctx ->
                                TextView(ctx).apply {
                                    textSize = 14f
                                    setLineSpacing(4f, 1.18f)
                                    setTextIsSelectable(true)
                                    movementMethod = LinkMovementMethod.getInstance()
                                }
                            },
                            update = { tv ->
                                tv.setTextColor(textColor)
                                tv.setLinkTextColor(linkColor)
                                val spanned = HtmlCompat.fromHtml(
                                    job.descriptionHtml,
                                    HtmlCompat.FROM_HTML_MODE_LEGACY
                                )
                                tv.text = spanned.trimEnd()
                            }
                        )
                    } else {
                        Text(
                            job.descriptionText.ifBlank { job.teaser.ifBlank { "No description archived." } },
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
}
