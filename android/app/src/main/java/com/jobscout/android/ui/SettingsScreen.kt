package com.jobscout.android.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.jobscout.android.data.ConfigRepository
import com.jobscout.android.model.LLMProviderConfig
import com.jobscout.android.model.ScannerConfig
import com.jobscout.android.model.SearchQueryConfig
import com.jobscout.android.network.LlmProviderRegistry

@Composable
fun SettingsScreen(
    config: ScannerConfig,
    configRepo: ConfigRepository,
    desktopSyncUrl: String,
    lastSyncSummary: String?,
    onSyncWithDesktop: (String) -> Unit,
    onPullProfileFromDesktop: (String) -> Unit,
    onSaveLlmConfig: (LLMProviderConfig) -> Unit,
    onSaveApiKey: (String, String) -> Unit,
    onAddCustomPreset: (String, String, String, String, String) -> Unit,
    onToggleQuery: (Int) -> Unit,
    onAddQuery: (SearchQueryConfig) -> Unit,
    onUpdateQuery: (Int, SearchQueryConfig) -> Unit,
    onDeleteQuery: (Int) -> Unit,
    onAddBlockedCompany: (String) -> Unit,
    onRemoveBlockedCompany: (String) -> Unit,
    onRescoreAll: () -> Unit
) {
    val llm = config.llm
    var activeProvider by remember(llm) { mutableStateOf(llm.activeProvider) }
    var geminiModel by remember(llm) { mutableStateOf(llm.geminiModel) }
    var geminiThinkingLevel by remember(llm) { mutableStateOf(llm.geminiThinkingLevel) }
    var deepseekModel by remember(llm) { mutableStateOf(llm.deepseekModel) }
    var openaiBaseUrl by remember(llm) { mutableStateOf(llm.openaiBaseUrl) }
    var openaiModel by remember(llm) { mutableStateOf(llm.openaiModel) }
    var anthropicModel by remember(llm) { mutableStateOf(llm.anthropicModel) }

    var geminiKeyInput by remember { mutableStateOf("") }
    var deepseekKeyInput by remember { mutableStateOf("") }
    var openaiKeyInput by remember { mutableStateOf("") }
    var anthropicKeyInput by remember { mutableStateOf("") }

    var syncUrlInput by remember(desktopSyncUrl) { mutableStateOf(desktopSyncUrl) }
    var showAddPresetDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 0. Desktop Wi-Fi Sync
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Desktop Wi-Fi Sync", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "Sends Shortlisted/Tailored/Applied jobs (without PDFs) to Desktop's \"From Phone\" tab, overwrites Desktop Profile, and syncs Applied statuses both ways.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = syncUrlInput,
                    onValueChange = { syncUrlInput = it },
                    label = { Text("Desktop URL (from PC's Phone Sync button)") },
                    placeholder = { Text("http://192.168.1.50:8765") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { onSyncWithDesktop(syncUrlInput) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Sync Now")
                    }
                    OutlinedButton(
                        onClick = { onPullProfileFromDesktop(syncUrlInput) }
                    ) {
                        Text("Pull Profile")
                    }
                }
                if (!lastSyncSummary.isNullOrBlank()) {
                    Text(
                        lastSyncSummary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        // 1. AI Providers & API Keys
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("AI Providers & API Keys", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    FilledTonalButton(onClick = { showAddPresetDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Add Provider")
                    }
                }

                Text("Active Provider", style = MaterialTheme.typography.labelMedium)
                Column {
                    LlmProviderRegistry.builtInProviders.forEach { meta ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = activeProvider == meta.id,
                                onClick = { activeProvider = meta.id }
                            )
                            Text("${meta.name} (${configRepo.maskKey(configRepo.getApiKey(meta.defaultKeyEnv))})")
                        }
                    }
                    llm.customPresets.forEach { preset ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = activeProvider == preset.id,
                                onClick = { activeProvider = preset.id }
                            )
                            Text("${preset.label} · ${preset.model} (${configRepo.maskKey(configRepo.getApiKey(preset.apiKeyName))})")
                        }
                    }
                }

                HorizontalDivider()

                // DeepSeek
                OutlinedTextField(
                    value = deepseekModel,
                    onValueChange = { deepseekModel = it },
                    label = { Text("DeepSeek Model") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                ApiKeyRow(
                    label = "DEEPSEEK_API_KEY (${configRepo.maskKey(configRepo.getApiKey(llm.deepseekApiKeyEnv))})",
                    value = deepseekKeyInput,
                    onValueChange = { deepseekKeyInput = it },
                    onSave = {
                        onSaveApiKey(llm.deepseekApiKeyEnv, deepseekKeyInput)
                        deepseekKeyInput = ""
                    }
                )

                // Gemini
                OutlinedTextField(
                    value = geminiModel,
                    onValueChange = { geminiModel = it },
                    label = { Text("Gemini Model") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Text("Gemini Thinking Level", style = MaterialTheme.typography.labelMedium)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf("default", "minimal", "low", "medium", "high").forEach { level ->
                        FilterChip(
                            selected = geminiThinkingLevel.lowercase() == level,
                            onClick = { geminiThinkingLevel = level },
                            label = { Text(level.replaceFirstChar { it.uppercase() }) }
                        )
                    }
                }
                ApiKeyRow(
                    label = "GEMINI_API_KEY (${configRepo.maskKey(configRepo.getApiKey(llm.geminiApiKeyEnv))})",
                    value = geminiKeyInput,
                    onValueChange = { geminiKeyInput = it },
                    onSave = {
                        onSaveApiKey(llm.geminiApiKeyEnv, geminiKeyInput)
                        geminiKeyInput = ""
                    }
                )

                // OpenAI-Compatible
                OutlinedTextField(
                    value = openaiBaseUrl,
                    onValueChange = { openaiBaseUrl = it },
                    label = { Text("OpenAI-Compatible Base URL") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = openaiModel,
                    onValueChange = { openaiModel = it },
                    label = { Text("OpenAI-Compatible Model") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                ApiKeyRow(
                    label = "OPENAI_API_KEY (${configRepo.maskKey(configRepo.getApiKey(llm.openaiApiKeyEnv))})",
                    value = openaiKeyInput,
                    onValueChange = { openaiKeyInput = it },
                    onSave = {
                        onSaveApiKey(llm.openaiApiKeyEnv, openaiKeyInput)
                        openaiKeyInput = ""
                    }
                )

                // Anthropic
                OutlinedTextField(
                    value = anthropicModel,
                    onValueChange = { anthropicModel = it },
                    label = { Text("Anthropic Model") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                ApiKeyRow(
                    label = "ANTHROPIC_API_KEY (${configRepo.maskKey(configRepo.getApiKey(llm.anthropicApiKeyEnv))})",
                    value = anthropicKeyInput,
                    onValueChange = { anthropicKeyInput = it },
                    onSave = {
                        onSaveApiKey(llm.anthropicApiKeyEnv, anthropicKeyInput)
                        anthropicKeyInput = ""
                    }
                )

                Button(
                    onClick = {
                        onSaveLlmConfig(
                            llm.copy(
                                activeProvider = activeProvider,
                                geminiModel = geminiModel.trim(),
                                geminiThinkingLevel = geminiThinkingLevel.trim().lowercase(),
                                deepseekModel = deepseekModel.trim(),
                                openaiBaseUrl = openaiBaseUrl.trim(),
                                openaiModel = openaiModel.trim(),
                                anthropicModel = anthropicModel.trim()
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Save Provider Settings")
                }
            }
        }

        // 2. Search Profiles (Add / Edit / Delete / Toggle)
        var editingQuery by remember { mutableStateOf<Pair<Int?, SearchQueryConfig>?>(null) }
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("SEEK Search Queries (${config.defaultBoard})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    FilledTonalButton(onClick = {
                        editingQuery = null to SearchQueryConfig(
                            name = "",
                            keywords = "",
                            where = "All Auckland",
                            siteKey = if (config.defaultBoard == "seek_au") "AU-Main" else "NZ-Main",
                            dateRangeDays = 14,
                            maxPages = 2
                        )
                    }) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Add")
                    }
                }
                config.queries.forEachIndexed { idx, q ->
                    HorizontalDivider()
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(q.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            Text("${q.keywords} (${q.where})", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { editingQuery = idx to q }) {
                            Icon(Icons.Default.Edit, contentDescription = "Edit Query")
                        }
                        IconButton(onClick = { onDeleteQuery(idx) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete Query")
                        }
                        Switch(checked = q.enabled, onCheckedChange = { onToggleQuery(idx) })
                    }
                }
            }
        }

        editingQuery?.let { (idx, q) ->
            var qName by remember { mutableStateOf(q.name) }
            var qKeywords by remember { mutableStateOf(q.keywords) }
            var qWhere by remember { mutableStateOf(q.where) }
            var qDays by remember { mutableStateOf((q.dateRangeDays ?: 14).toString()) }
            var qPages by remember { mutableStateOf(q.maxPages.toString()) }

            AlertDialog(
                onDismissRequest = { editingQuery = null },
                title = { Text(if (idx == null) "Add Search Query" else "Edit Search Query") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = qName, onValueChange = { qName = it }, label = { Text("Name") }, singleLine = true)
                        OutlinedTextField(value = qKeywords, onValueChange = { qKeywords = it }, label = { Text("Keywords (e.g. embedded OR pcb)") })
                        OutlinedTextField(value = qWhere, onValueChange = { qWhere = it }, label = { Text("Where (e.g. All Auckland)") }, singleLine = true)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = qDays,
                                onValueChange = { qDays = it },
                                label = { Text("Days") },
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = qPages,
                                onValueChange = { qPages = it },
                                label = { Text("Pages") },
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                        }
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        if (qName.isNotBlank() && qKeywords.isNotBlank()) {
                            val updated = q.copy(
                                name = qName.trim(),
                                keywords = qKeywords.trim(),
                                where = qWhere.trim(),
                                dateRangeDays = qDays.toIntOrNull() ?: 14,
                                maxPages = (qPages.toIntOrNull() ?: 2).coerceIn(1, 5)
                            )
                            if (idx == null) onAddQuery(updated) else onUpdateQuery(idx, updated)
                            editingQuery = null
                        }
                    }) { Text("Save") }
                },
                dismissButton = { TextButton(onClick = { editingQuery = null }) { Text("Cancel") } }
            )
        }

        // 3. Employer Guardrails (Blocked Companies)
        var newBlocked by remember { mutableStateOf("") }
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Blocked Employers (${config.blockedCompanies.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    TextButton(onClick = onRescoreAll) { Text("Re-score Jobs") }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = newBlocked,
                        onValueChange = { newBlocked = it },
                        label = { Text("Add company to block") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    FilledTonalButton(onClick = {
                        if (newBlocked.isNotBlank()) {
                            onAddBlockedCompany(newBlocked)
                            newBlocked = ""
                        }
                    }) { Text("Block") }
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    config.blockedCompanies.forEach { company ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(company, style = MaterialTheme.typography.bodyMedium)
                            IconButton(onClick = { onRemoveBlockedCompany(company) }) {
                                Icon(Icons.Default.Close, contentDescription = "Unblock $company", modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddPresetDialog) {
        var label by remember { mutableStateOf("") }
        var baseUrl by remember { mutableStateOf("https://openrouter.ai/api/v1") }
        var model by remember { mutableStateOf("") }
        var keyName by remember { mutableStateOf("OPENROUTER_API_KEY") }
        var keyVal by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showAddPresetDialog = false },
            title = { Text("Add Custom OpenAI-Compatible Provider") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = label, onValueChange = { label = it }, label = { Text("Name (e.g. OpenRouter / Groq)") }, singleLine = true)
                    OutlinedTextField(value = baseUrl, onValueChange = { baseUrl = it }, label = { Text("Base URL (/v1)") }, singleLine = true)
                    OutlinedTextField(value = model, onValueChange = { model = it }, label = { Text("Model ID") }, singleLine = true)
                    OutlinedTextField(value = keyName, onValueChange = { keyName = it }, label = { Text("Key Name") }, singleLine = true)
                    OutlinedTextField(
                        value = keyVal,
                        onValueChange = { keyVal = it },
                        label = { Text("API Key") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (label.isNotBlank() && baseUrl.isNotBlank() && model.isNotBlank()) {
                            onAddCustomPreset(label, baseUrl, model, keyName, keyVal)
                            showAddPresetDialog = false
                        }
                    }
                ) { Text("Add & Activate") }
            },
            dismissButton = {
                TextButton(onClick = { showAddPresetDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun ApiKeyRow(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    onSave: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.weight(1f),
            singleLine = true
        )
        FilledTonalButton(onClick = onSave) {
            Text("Set")
        }
    }
}
