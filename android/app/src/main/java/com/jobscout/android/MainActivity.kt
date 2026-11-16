package com.jobscout.android

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.jobscout.android.ui.JobDetailScreen
import com.jobscout.android.ui.JobsScreen
import com.jobscout.android.ui.MainViewModel
import com.jobscout.android.ui.ProfileScreen
import com.jobscout.android.ui.SettingsScreen

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleShareIntent(intent)

        setContent {
            MaterialTheme {
                JobScoutApp(vm)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShareIntent(intent)
    }

    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
            if ("seek." in sharedText.lowercase() || Regex("\\b\\d{7,10}\\b").containsMatchIn(sharedText)) {
                vm.importJobUrl(sharedText, probeSalary = true)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun JobScoutApp(vm: MainViewModel) {
    val jobs by vm.jobs.collectAsState()
    val selectedJob by vm.selectedJob.collectAsState()
    val scanProgress by vm.scanProgress.collectAsState()
    val config by vm.config.collectAsState()
    val profile by vm.profile.collectAsState()
    val profileAudit by vm.profileAudit.collectAsState()
    val searchText by vm.searchText.collectAsState()
    val statusFilter by vm.statusFilter.collectAsState()
    val busyMessage by vm.busyMessage.collectAsState()
    val toastMessage by vm.toastMessage.collectAsState()
    val tailorQueue by vm.tailorQueue.collectAsState()
    val activeTailorId by vm.activeTailorId.collectAsState()
    val undoLabel by vm.undoLabel.collectAsState()
    val desktopSyncUrl by vm.desktopSyncUrl.collectAsState()
    val lastSyncSummary by vm.lastSyncSummary.collectAsState()

    var currentTab by remember { mutableIntStateOf(0) }
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    var lastBackPressMs by remember { mutableLongStateOf(0L) }

    BackHandler {
        when {
            selectedJob != null -> vm.selectJob(null)
            currentTab != 0 -> currentTab = 0
            else -> {
                val now = System.currentTimeMillis()
                if (now - lastBackPressMs <= 2000L) {
                    (context as? Activity)?.finish()
                } else {
                    lastBackPressMs = now
                    Toast.makeText(context, "Press back again to exit", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    LaunchedEffect(selectedJob?.id, currentTab) {
        snackbarHostState.currentSnackbarData?.dismiss()
    }

    LaunchedEffect(toastMessage) {
        toastMessage?.let {
            vm.clearToast()
            snackbarHostState.showSnackbar(
                message = it,
                duration = if (it.contains("failed", ignoreCase = true)) SnackbarDuration.Long else SnackbarDuration.Short
            )
        }
    }

    LaunchedEffect(undoLabel) {
        undoLabel?.let { label ->
            val result = snackbarHostState.showSnackbar(
                message = label,
                actionLabel = "Undo",
                duration = SnackbarDuration.Short
            )
            if (result == SnackbarResult.ActionPerformed) {
                vm.undoLastSwipe()
            } else {
                vm.clearUndo()
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (selectedJob != null) {
            JobDetailScreen(
                job = selectedJob!!,
                snackbarHostState = snackbarHostState,
                onBack = { vm.selectJob(null) },
                onProbeSalary = { vm.probeSalary(it) },
                onAnalyzeFit = { vm.analyzeFit(it) },
                onTailorAndPdf = { id, angle -> vm.tailorAndRenderPdf(id, angle) },
                onOpenPdf = { vm.sharePdf(it) },
                onStatusChange = { id, status -> vm.updateStatus(id, status) }
            )
        } else {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = {
                            Text(
                                when (currentTab) {
                                    0 -> "Job Scout"
                                    1 -> "My Profile"
                                    else -> "Settings & AI"
                                }
                            )
                        }
                    )
                },
                bottomBar = {
                    NavigationBar {
                        NavigationBarItem(
                            selected = currentTab == 0,
                            onClick = { currentTab = 0 },
                            icon = { Icon(Icons.Default.Work, contentDescription = null) },
                            label = { Text("Jobs") }
                        )
                        NavigationBarItem(
                            selected = currentTab == 1,
                            onClick = { currentTab = 1 },
                            icon = { Icon(Icons.Default.Person, contentDescription = null) },
                            label = { Text("Profile") }
                        )
                        NavigationBarItem(
                            selected = currentTab == 2,
                            onClick = { currentTab = 2 },
                            icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                            label = { Text("Settings") }
                        )
                    }
                },
                snackbarHost = { SnackbarHost(snackbarHostState) }
            ) { padding ->
                Box(modifier = Modifier.padding(padding).fillMaxSize()) {
                    when (currentTab) {
                        0 -> JobsScreen(
                            jobs = jobs,
                            scanProgress = scanProgress,
                            searchText = searchText,
                            statusFilter = statusFilter,
                            tailorQueue = tailorQueue,
                            activeTailorId = activeTailorId,
                            onSearchChange = { vm.setSearchText(it) },
                            onStatusFilterChange = { vm.setStatusFilter(it) },
                            onRunScan = { vm.runScan() },
                            onImportUrl = { vm.importJobUrl(it) },
                            onSelectJob = { vm.selectJob(it) },
                            onSwipeStatus = { job, status -> vm.swipeStatusChange(job, status) },
                            onEnqueueTailor = { vm.enqueueTailor(it) },
                            onResetTailored = { vm.resetTailoredToShortlisted(it) }
                        )
                        1 -> ProfileScreen(
                            profile = profile,
                            auditSuggestions = profileAudit,
                            onSaveProfile = { vm.saveProfile(it) },
                            onRunAiAudit = { vm.auditProfileWithAi() }
                        )
                        else -> SettingsScreen(
                            config = config,
                            configRepo = vm.configRepo,
                            desktopSyncUrl = desktopSyncUrl,
                            lastSyncSummary = lastSyncSummary,
                            onSyncWithDesktop = { vm.syncWithDesktop(it) },
                            onPullProfileFromDesktop = { vm.pullProfileFromDesktop(it) },
                            onSaveLlmConfig = { vm.updateLlmConfig(it) },
                            onSaveApiKey = { k, v -> vm.saveApiKey(k, v) },
                            onAddCustomPreset = { l, u, m, kn, kv -> vm.addCustomProviderPreset(l, u, m, kn, kv) },
                            onToggleQuery = { vm.toggleQueryEnabled(it) },
                            onAddQuery = { vm.addSearchQuery(it) },
                            onUpdateQuery = { i, q -> vm.updateSearchQuery(i, q) },
                            onDeleteQuery = { vm.deleteSearchQuery(it) },
                            onAddBlockedCompany = { vm.addBlockedCompany(it) },
                            onRemoveBlockedCompany = { vm.removeBlockedCompany(it) },
                            onRescoreAll = { vm.rescoreAllJobs() }
                        )
                    }
                }
            }
        }

        if (busyMessage != null) {
            Surface(
                color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.45f),
                modifier = Modifier.fillMaxSize()
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Card {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(12.dp))
                            Text(busyMessage ?: "", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}
