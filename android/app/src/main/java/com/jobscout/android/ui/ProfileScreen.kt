package com.jobscout.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jobscout.android.model.CandidateProfile
import com.jobscout.android.model.ExperienceItem
import com.jobscout.android.model.ProfileAuditSuggestions
import com.jobscout.android.model.ProjectItem
import java.util.UUID

@Composable
fun ProfileScreen(
    profile: CandidateProfile,
    auditSuggestions: ProfileAuditSuggestions?,
    onSaveProfile: (CandidateProfile) -> Unit,
    onRunAiAudit: () -> Unit
) {
    var headline by remember(profile) { mutableStateOf(profile.headline) }
    var baseSummary by remember(profile) { mutableStateOf(profile.baseSummary) }
    var location by remember(profile) { mutableStateOf(profile.location) }
    var workRights by remember(profile) { mutableStateOf(profile.workRights) }
    var languages by remember(profile) { mutableStateOf(profile.languages) }

    var editingProject by remember { mutableStateOf<Pair<Int?, ProjectItem>?>(null) }
    var editingSkillCat by remember { mutableStateOf<Pair<String?, String>?>(null) }
    var editingExp by remember { mutableStateOf<Pair<Int?, ExperienceItem>?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. AI Profile Suggestions ("What did I miss?")
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f))
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("AI Profile Check", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(
                            "Check what skills, tools, or bio points you might have missed.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Button(onClick = onRunAiAudit) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Check")
                    }
                }

                auditSuggestions?.let { audit ->
                    HorizontalDivider()
                    if (audit.overallFeedback.isNotBlank()) {
                        Text(audit.overallFeedback, style = MaterialTheme.typography.bodySmall)
                    }
                    if (audit.missingSkillsFromProjects.isNotEmpty()) {
                        Text("Missing from Skills (found in your projects/work):", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                        audit.missingSkillsFromProjects.forEach { sk ->
                            val alreadyAdded = profile.skills.values.flatten().any { it.equals(sk.trim(), ignoreCase = true) }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("• $sk", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                                TextButton(
                                    enabled = !alreadyAdded,
                                    onClick = {
                                        val firstCat = profile.skills.keys.firstOrNull() ?: "Technical Skills"
                                        val curList = profile.skills[firstCat] ?: emptyList()
                                        val updatedSkills = profile.skills.toMutableMap().apply {
                                            put(firstCat, curList + sk.trim())
                                        }
                                        onSaveProfile(profile.copy(skills = updatedSkills))
                                    }
                                ) {
                                    Text(if (alreadyAdded) "Added ✓" else "+ Add")
                                }
                            }
                        }
                    }
                    if (audit.marketGapSuggestions.isNotEmpty()) {
                        Text("Common in Target Jobs (add only if you have them):", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                        audit.marketGapSuggestions.forEach { g ->
                            val alreadyAdded = profile.skills.values.flatten().any { it.equals(g.trim(), ignoreCase = true) }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("• $g", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                                TextButton(
                                    enabled = !alreadyAdded,
                                    onClick = {
                                        val firstCat = profile.skills.keys.firstOrNull() ?: "Technical Skills"
                                        val curList = profile.skills[firstCat] ?: emptyList()
                                        val updatedSkills = profile.skills.toMutableMap().apply {
                                            put(firstCat, curList + g.trim())
                                        }
                                        onSaveProfile(profile.copy(skills = updatedSkills))
                                    }
                                ) {
                                    Text(if (alreadyAdded) "Added ✓" else "+ Add")
                                }
                            }
                        }
                    }
                    if (audit.bulletImprovements.isNotEmpty()) {
                        Text("Bullet Improvements:", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                        audit.bulletImprovements.forEach { b ->
                            Text("• $b", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (audit.suggestedBioTweak.isNotBlank()) {
                        Text("Suggested Bio:", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                        Text(audit.suggestedBioTweak, style = MaterialTheme.typography.bodySmall)
                        val bioApplied = profile.baseSummary.trim() == audit.suggestedBioTweak.trim()
                        OutlinedButton(
                            enabled = !bioApplied,
                            onClick = {
                                baseSummary = audit.suggestedBioTweak
                                onSaveProfile(profile.copy(baseSummary = audit.suggestedBioTweak))
                            }
                        ) {
                            Text(if (bioApplied) "Applied ✓" else "Apply Suggested Bio")
                        }
                    }
                }
            }
        }

        // 2. Bio & Header
        val bioDirty = headline.trim() != profile.headline.trim() ||
            baseSummary.trim() != profile.baseSummary.trim() ||
            location.trim() != profile.location.trim() ||
            workRights.trim() != profile.workRights.trim() ||
            languages.trim() != profile.languages.trim()

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Bio & Header (${profile.fullName})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = headline,
                    onValueChange = { headline = it },
                    label = { Text("Headline") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = baseSummary,
                    onValueChange = { baseSummary = it },
                    label = { Text("Bio / Base Summary") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3
                )
                OutlinedTextField(
                    value = location,
                    onValueChange = { location = it },
                    label = { Text("Location") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = workRights,
                    onValueChange = { workRights = it },
                    label = { Text("Work Rights") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = languages,
                    onValueChange = { languages = it },
                    label = { Text("Languages") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Button(
                    onClick = {
                        onSaveProfile(
                            profile.copy(
                                headline = headline.trim(),
                                baseSummary = baseSummary.trim(),
                                location = location.trim(),
                                workRights = workRights.trim(),
                                languages = languages.trim()
                            )
                        )
                    },
                    enabled = bioDirty,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (bioDirty) "Save Bio & Header" else "Bio & Header Saved ✓")
                }
            }
        }

        // 3. Projects (with Tools & Skills tags)
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Projects (${profile.projects.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    FilledTonalButton(onClick = {
                        editingProject = null to ProjectItem(
                            id = "proj_${UUID.randomUUID().toString().take(6)}",
                            name = "",
                            tags = emptyList(),
                            bullets = emptyList()
                        )
                    }) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Add Project")
                    }
                }
                profile.projects.forEachIndexed { idx, proj ->
                    HorizontalDivider()
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(proj.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            if (proj.tags.isNotEmpty()) {
                                Text(
                                    "Tools/Skills: ${proj.tags.joinToString(", ")}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            proj.bullets.forEach { b ->
                                Text("• $b", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        IconButton(onClick = { editingProject = idx to proj }) {
                            Icon(Icons.Default.Edit, contentDescription = "Edit Project")
                        }
                        IconButton(onClick = {
                            onSaveProfile(profile.copy(projects = profile.projects.filterIndexed { i, _ -> i != idx }))
                        }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete Project")
                        }
                    }
                }
            }
        }

        // 4. Skills by Category
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Skills (${profile.skills.size} categories)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    FilledTonalButton(onClick = { editingSkillCat = null to "" }) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Add Category")
                    }
                }
                profile.skills.forEach { (cat, items) ->
                    HorizontalDivider()
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(cat, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            Text(items.joinToString(", "), style = MaterialTheme.typography.bodySmall)
                        }
                        IconButton(onClick = { editingSkillCat = cat to items.joinToString(", ") }) {
                            Icon(Icons.Default.Edit, contentDescription = "Edit Skills")
                        }
                        IconButton(onClick = {
                            val updated = profile.skills.toMutableMap().apply { remove(cat) }
                            onSaveProfile(profile.copy(skills = updated))
                        }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete Category")
                        }
                    }
                }
            }
        }

        // 5. Work Experience
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Work Experience (${profile.experiences.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    FilledTonalButton(onClick = {
                        editingExp = null to ExperienceItem(
                            id = "exp_${UUID.randomUUID().toString().take(6)}",
                            role = "",
                            company = "",
                            location = "Auckland",
                            dates = "",
                            tags = emptyList(),
                            bullets = emptyList()
                        )
                    }) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Add Role")
                    }
                }
                profile.experiences.forEachIndexed { idx, exp ->
                    HorizontalDivider()
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("${exp.role} — ${exp.company}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            Text("${exp.dates} · ${exp.location}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            exp.bullets.forEach { b ->
                                Text("• $b", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        IconButton(onClick = { editingExp = idx to exp }) {
                            Icon(Icons.Default.Edit, contentDescription = "Edit Role")
                        }
                        IconButton(onClick = {
                            onSaveProfile(profile.copy(experiences = profile.experiences.filterIndexed { i, _ -> i != idx }))
                        }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete Role")
                        }
                    }
                }
            }
        }
    }

    // Dialogs
    editingProject?.let { (index, item) ->
        var name by remember { mutableStateOf(item.name) }
        var tagsStr by remember { mutableStateOf(item.tags.joinToString(", ")) }
        var bulletsStr by remember { mutableStateOf(item.bullets.joinToString("\n")) }

        AlertDialog(
            onDismissRequest = { editingProject = null },
            title = { Text(if (index == null) "Add Project" else "Edit Project") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Project Name") })
                    OutlinedTextField(
                        value = tagsStr,
                        onValueChange = { tagsStr = it },
                        label = { Text("Tools & Skills Used (comma-separated)") },
                        placeholder = { Text("esp32, altium, c++, calibration") }
                    )
                    OutlinedTextField(
                        value = bulletsStr,
                        onValueChange = { bulletsStr = it },
                        label = { Text("Bullets (one per line)") },
                        minLines = 3
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (name.isNotBlank()) {
                        val updatedItem = item.copy(
                            name = name.trim(),
                            tags = tagsStr.split(",").map { it.trim() }.filter { it.isNotEmpty() },
                            bullets = bulletsStr.lines().map { it.trim().removePrefix("•").trim() }.filter { it.isNotEmpty() }
                        )
                        val newList = if (index == null) {
                            profile.projects + updatedItem
                        } else {
                            profile.projects.mapIndexed { i, p -> if (i == index) updatedItem else p }
                        }
                        onSaveProfile(profile.copy(projects = newList))
                        editingProject = null
                    }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editingProject = null }) { Text("Cancel") } }
        )
    }

    editingSkillCat?.let { (origCat, origItems) ->
        var catName by remember { mutableStateOf(origCat ?: "") }
        var itemsStr by remember { mutableStateOf(origItems) }

        AlertDialog(
            onDismissRequest = { editingSkillCat = null },
            title = { Text(if (origCat == null) "Add Skill Category" else "Edit Skill Category") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = catName, onValueChange = { catName = it }, label = { Text("Category Name") }, singleLine = true)
                    OutlinedTextField(
                        value = itemsStr,
                        onValueChange = { itemsStr = it },
                        label = { Text("Skills (comma-separated)") },
                        minLines = 3
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    val cleanCat = catName.trim()
                    if (cleanCat.isNotEmpty()) {
                        val list = itemsStr.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                        val updatedMap = profile.skills.toMutableMap().apply {
                            if (origCat != null && origCat != cleanCat) remove(origCat)
                            put(cleanCat, list)
                        }
                        onSaveProfile(profile.copy(skills = updatedMap))
                        editingSkillCat = null
                    }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editingSkillCat = null }) { Text("Cancel") } }
        )
    }

    editingExp?.let { (index, exp) ->
        var role by remember { mutableStateOf(exp.role) }
        var company by remember { mutableStateOf(exp.company) }
        var dates by remember { mutableStateOf(exp.dates) }
        var loc by remember { mutableStateOf(exp.location) }
        var bulletsStr by remember { mutableStateOf(exp.bullets.joinToString("\n")) }

        AlertDialog(
            onDismissRequest = { editingExp = null },
            title = { Text(if (index == null) "Add Experience" else "Edit Experience") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(value = role, onValueChange = { role = it }, label = { Text("Role") }, singleLine = true)
                    OutlinedTextField(value = company, onValueChange = { company = it }, label = { Text("Company") }, singleLine = true)
                    OutlinedTextField(value = dates, onValueChange = { dates = it }, label = { Text("Dates") }, singleLine = true)
                    OutlinedTextField(value = loc, onValueChange = { loc = it }, label = { Text("Location") }, singleLine = true)
                    OutlinedTextField(value = bulletsStr, onValueChange = { bulletsStr = it }, label = { Text("Bullets (one per line)") }, minLines = 3)
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (role.isNotBlank() && company.isNotBlank()) {
                        val updated = exp.copy(
                            role = role.trim(),
                            company = company.trim(),
                            dates = dates.trim(),
                            location = loc.trim(),
                            bullets = bulletsStr.lines().map { it.trim().removePrefix("•").trim() }.filter { it.isNotEmpty() }
                        )
                        val newList = if (index == null) {
                            profile.experiences + updated
                        } else {
                            profile.experiences.mapIndexed { i, e -> if (i == index) updated else e }
                        }
                        onSaveProfile(profile.copy(experiences = newList))
                        editingExp = null
                    }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editingExp = null }) { Text("Cancel") } }
        )
    }
}
