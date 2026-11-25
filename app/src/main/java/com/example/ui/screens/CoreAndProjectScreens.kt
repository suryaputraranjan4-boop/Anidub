package com.example.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Outbox
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.local.EpisodeEntity
import com.example.data.local.ProjectEntity
import com.example.engine.HinglishTtsNormalizer
import com.example.engine.ModelDownloadManager
import com.example.ui.components.AudioWaveformCanvas
import com.example.ui.components.MetadataChipRow
import com.example.ui.components.StatusPill
import com.example.ui.components.StudioCard
import com.example.ui.components.StudioEmptyState
import com.example.ui.components.StudioSectionHeader
import com.example.ui.components.WarningBanner
import com.example.ui.components.formatBytes
import com.example.ui.components.formatDurationCompact
import com.example.ui.components.formatShortDate
import com.example.ui.components.statusColorFor
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.StudioAmber
import com.example.ui.theme.StudioBorder
import com.example.ui.theme.StudioBorderSubtle
import com.example.ui.theme.StudioCrimson
import com.example.ui.theme.StudioCyan
import com.example.ui.theme.StudioEmerald
import com.example.ui.theme.StudioSurface
import com.example.ui.theme.StudioSurfaceElevated
import com.example.ui.theme.StudioTextMuted
import com.example.ui.theme.StudioTextPrimary
import com.example.ui.theme.StudioTextSecondary
import com.example.ui.theme.StudioViolet
import com.example.viewmodel.MainTab
import com.example.viewmodel.MediaInspectionStatus
import com.example.viewmodel.ProductionSection
import com.example.viewmodel.ProjectSortOrder
import com.example.viewmodel.StudioOverlay
import com.example.viewmodel.StudioViewModel

/**
 * 1. HOME DASHBOARD SCREEN
 * Strictly displays real database records, real storage availability, real models, real jobs,
 * and real export history. When no projects exist, displays a polished Empty State with
 * "Create Your First Project".
 */
@Composable
fun HomeDashboardScreen(viewModel: StudioViewModel) {
    val projects by viewModel.projects.collectAsState()
    val episodes by viewModel.allEpisodes.collectAsState()
    val models by viewModel.models.collectAsState()
    val recentJobs by viewModel.allAgentJobHistory.collectAsState()
    val recentExports by viewModel.allExports.collectAsState()
    val activeEpisode by viewModel.activeEpisode.collectAsState()
    val activeProject by viewModel.activeProject.collectAsState()

    val availableBytes = remember { viewModel.modelDownloadManager.getAvailableStorageBytes() }
    val modelsUsedBytes = remember(models) { viewModel.modelDownloadManager.getModelsStorageUsedBytes() }
    val localReadyCount = models.count {
        ModelDownloadManager.isLocalRuntime(it.runtimeCompatibility) &&
            (it.downloadState == "INSTALLED" || it.downloadState == "BUILT_IN")
    }
    val remoteReadyCount = models.count {
        !ModelDownloadManager.isLocalRuntime(it.runtimeCompatibility) &&
            (it.downloadState == "INSTALLED" || it.runtimeCompatibility == "EXTERNAL_API")
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("home_dashboard_list"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Studio Welcome Header + Primary Quick Actions
        item {
            StudioCard(padding = 18.dp) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "AnimeDub Studio",
                            style = MaterialTheme.typography.headlineSmall,
                            color = StudioTextPrimary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (projects.isEmpty()) {
                                "Audio-first Japanese to Roman Hinglish dubbing workstation."
                            } else {
                                "${projects.size} project(s) • ${episodes.size} episode(s) in local workspace"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = StudioTextSecondary
                        )
                    }
                    StatusPill(
                        text = "READY",
                        color = StudioEmerald
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = { viewModel.openGuidedNewProject() },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag("home_create_project_btn"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = StudioViolet,
                            contentColor = Color(0xFF0A0B0F)
                        ),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Create Project", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }

                    OutlinedButton(
                        onClick = { viewModel.openGuidedImportEpisode(activeProject) },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag("home_import_episode_btn"),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, StudioBorder)
                    ) {
                        Icon(Icons.Default.FileUpload, contentDescription = null, tint = StudioCyan, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Import Episode", color = StudioTextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }

        // Active Episode Resume Card OR Polished Empty State
        if (projects.isEmpty()) {
            item {
                StudioEmptyState(
                    icon = Icons.Default.Movie,
                    title = "No Dubbing Projects Yet",
                    description = "Create a project and import a real Japanese anime video or audio file to begin stem separation, audio-only speaker profiling, Roman Hinglish translation, and multitrack mixing.",
                    primaryActionLabel = "Create Your First Project",
                    onPrimaryAction = { viewModel.openGuidedNewProject() },
                    secondaryActionLabel = "Explore Model Library",
                    onSecondaryAction = { viewModel.selectTab(MainTab.MODELS) },
                    testTagPrefix = "home_empty_projects"
                )
            }
        } else {
            // Continue Active Episode
            if (activeEpisode != null) {
                item {
                    val ep = activeEpisode!!
                    val completedStages = (1..14).count { (ep.completedStagesMask and (1 shl it)) != 0 }
                    StudioSectionHeader(
                        title = "Active Production",
                        trailing = {
                            TextButton(onClick = { viewModel.selectTab(MainTab.PRODUCTION) }) {
                                Text("Open Studio", color = StudioViolet)
                            }
                        }
                    )
                    StudioCard(
                        elevated = true,
                        accentBorder = StudioViolet.copy(alpha = 0.45f),
                        onClick = { viewModel.selectTab(MainTab.PRODUCTION) }
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = activeProject?.title ?: "Active Project",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = StudioViolet,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "Ep ${ep.episodeNumber} — ${ep.episodeTitle}",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = StudioTextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            StatusPill(
                                text = "$completedStages / 14",
                                color = statusColorFor(ep.pipelineStatus)
                            )
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        LinearProgressIndicator(
                            progress = { completedStages / 14f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = StudioViolet,
                            trackColor = StudioBorder
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "${ep.currentStageName} • ${ep.videoFileName.ifBlank { "No media" }} • ${formatDurationCompact(ep.durationMs)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = StudioTextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // Recent Projects from real Room DB
            item {
                StudioSectionHeader(
                    title = "Recent Projects",
                    trailing = {
                        TextButton(onClick = { viewModel.selectTab(MainTab.PROJECTS) }) {
                            Text("All (${projects.size})", color = StudioCyan)
                        }
                    }
                )
            }
            items(projects.take(3), key = { it.id }) { proj ->
                val projEpisodes = episodes.filter { it.projectId == proj.id }
                val latestEp = projEpisodes.maxByOrNull { it.updatedAt }
                StudioCard(
                    onClick = {
                        if (latestEp != null) {
                            viewModel.selectActiveEpisode(latestEp.id, proj.id, navigateToProduction = true)
                        } else {
                            viewModel.openGuidedImportEpisode(proj)
                        }
                    }
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = proj.title,
                                style = MaterialTheme.typography.titleSmall,
                                color = StudioTextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${proj.animeSeriesName} • ${projEpisodes.size} episode(s)",
                                style = MaterialTheme.typography.bodySmall,
                                color = StudioTextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        StatusPill(
                            text = latestEp?.pipelineStatus ?: "NO MEDIA",
                            color = statusColorFor(latestEp?.pipelineStatus ?: "PENDING")
                        )
                    }
                }
            }
        }

        // Real System Status: Models + Local Storage
        item {
            StudioSectionHeader(
                title = "System & Models",
                subtitle = "Verified runtime availability and device storage"
            )
            StudioCard {
                MetadataChipRow(
                    items = listOf(
                        "Local Ready" to "$localReadyCount models",
                        "Cloud / API" to "$remoteReadyCount active",
                        "Free Space" to formatBytes(availableBytes)
                    )
                )
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(Icons.Default.Storage, contentDescription = null, tint = StudioTextMuted, modifier = Modifier.size(16.dp))
                        Text(
                            text = "Downloaded model cache: ${formatBytes(modelsUsedBytes)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = StudioTextSecondary
                        )
                    }
                    TextButton(onClick = { viewModel.selectTab(MainTab.MODELS) }) {
                        Text("Manage", color = StudioCyan)
                    }
                }
            }
        }

        // Actual Processing Jobs
        item {
            StudioSectionHeader(
                title = "Recent Processing Jobs",
                trailing = if (recentJobs.isNotEmpty()) {
                    {
                        TextButton(onClick = { viewModel.openOverlay(StudioOverlay.PROCESSING_HISTORY) }) {
                            Text("History", color = StudioViolet)
                        }
                    }
                } else null
            )
            if (recentJobs.isEmpty()) {
                StudioCard(padding = 14.dp) {
                    Text(
                        text = "No pipeline jobs have been run yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = StudioTextMuted
                    )
                }
            } else {
                StudioCard(padding = 12.dp) {
                    recentJobs.take(4).forEachIndexed { idx, job ->
                        if (idx > 0) HorizontalDivider(color = StudioBorderSubtle, modifier = Modifier.padding(vertical = 8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Stage ${job.stageNumber} • ${job.agentName}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = StudioTextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = job.currentOperation,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = StudioTextSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            StatusPill(text = job.status, color = statusColorFor(job.status))
                        }
                    }
                }
            }
        }

        // Recent Export History
        item {
            StudioSectionHeader(title = "Recent Exports")
            if (recentExports.isEmpty()) {
                StudioCard(padding = 14.dp) {
                    Text(
                        text = "No dubbed audio or video files exported yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = StudioTextMuted
                    )
                }
            } else {
                StudioCard(padding = 12.dp) {
                    recentExports.take(4).forEachIndexed { idx, exp ->
                        if (idx > 0) HorizontalDivider(color = StudioBorderSubtle, modifier = Modifier.padding(vertical = 8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Outbox,
                                    contentDescription = null,
                                    tint = StudioEmerald,
                                    modifier = Modifier.size(18.dp)
                                )
                                Column {
                                    Text(
                                        text = exp.outputFilePath.substringAfterLast('/'),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = StudioTextPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "${exp.exportFormat.replace('_', ' ')} • ${formatBytes(exp.fileSizeBytes)} • ${formatShortDate(exp.exportedAt)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = StudioTextSecondary
                                    )
                                }
                            }
                            StatusPill(text = exp.status, color = StudioEmerald)
                        }
                    }
                }
            }
        }
    }
}

/**
 * 2. PROJECT LIBRARY SCREEN
 * Search, Sort, Expandable Episode Lists, Add Episode, Delete Project, and Polished Empty State.
 */
@Composable
fun ProjectLibraryScreen(viewModel: StudioViewModel) {
    val projects by viewModel.projects.collectAsState()
    val allEpisodes by viewModel.allEpisodes.collectAsState()
    val activeEpisodeId by viewModel.activeEpisodeId.collectAsState()
    val searchQuery by viewModel.projectSearchQuery.collectAsState()
    val sortOrder by viewModel.projectSortOrder.collectAsState()

    var expandedProjectIds by remember { mutableStateOf(setOf<Long>()) }
    var projectPendingDelete by remember { mutableStateOf<ProjectEntity?>(null) }

    val filteredProjects = remember(projects, searchQuery, sortOrder) {
        val q = searchQuery.trim()
        val matched = if (q.isBlank()) projects else projects.filter {
            it.title.contains(q, ignoreCase = true) || it.animeSeriesName.contains(q, ignoreCase = true)
        }
        when (sortOrder) {
            ProjectSortOrder.RECENT -> matched.sortedByDescending { it.updatedAt }
            ProjectSortOrder.TITLE_ASC -> matched.sortedBy { it.title.lowercase() }
            ProjectSortOrder.SERIES -> matched.sortedBy { it.animeSeriesName.lowercase() }
        }
    }

    if (projectPendingDelete != null) {
        val target = projectPendingDelete!!
        AlertDialog(
            onDismissRequest = { projectPendingDelete = null },
            containerColor = StudioSurfaceElevated,
            title = { Text("Delete Project?", color = StudioTextPrimary) },
            text = {
                Text(
                    "Remove '${target.title}' and its local workspace files? This cannot be undone.",
                    color = StudioTextSecondary
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteProject(target.id)
                        projectPendingDelete = null
                    }
                ) {
                    Text("Delete", color = StudioCrimson)
                }
            },
            dismissButton = {
                TextButton(onClick = { projectPendingDelete = null }) {
                    Text("Cancel", color = StudioTextSecondary)
                }
            }
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("project_library_list"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            StudioSectionHeader(
                title = "Projects",
                subtitle = "${projects.size} project(s) • ${allEpisodes.size} episode(s)",
                trailing = {
                    Button(
                        onClick = { viewModel.openGuidedNewProject() },
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("projects_new_btn"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = StudioViolet,
                            contentColor = Color(0xFF0A0B0F)
                        ),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("New Project")
                    }
                }
            )
        }

        if (projects.isNotEmpty()) {
            item {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { viewModel.setProjectSearchQuery(it) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("projects_search_input"),
                    placeholder = { Text("Search projects or series…", color = StudioTextMuted) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = StudioTextMuted) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = StudioViolet,
                        unfocusedBorderColor = StudioBorder,
                        focusedContainerColor = StudioSurface,
                        unfocusedContainerColor = StudioSurface,
                        focusedTextColor = StudioTextPrimary,
                        unfocusedTextColor = StudioTextPrimary
                    )
                )
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ProjectSortOrder.entries.forEach { order ->
                        FilterChip(
                            selected = sortOrder == order,
                            onClick = { viewModel.setProjectSortOrder(order) },
                            label = { Text(order.label, style = MaterialTheme.typography.labelSmall) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = StudioViolet.copy(alpha = 0.2f),
                                selectedLabelColor = StudioViolet,
                                containerColor = StudioSurface,
                                labelColor = StudioTextSecondary
                            )
                        )
                    }
                }
            }
        }

        if (projects.isEmpty()) {
            item {
                StudioEmptyState(
                    icon = Icons.Default.FolderOpen,
                    title = "Your Project Library Is Empty",
                    description = "Start a new anime dubbing project to organize episodes, speaker voice profiles, series dictionaries, and synchronized Hindi-Hinglish masters.",
                    primaryActionLabel = "Create Your First Project",
                    onPrimaryAction = { viewModel.openGuidedNewProject() },
                    testTagPrefix = "projects_empty"
                )
            }
        } else if (filteredProjects.isEmpty()) {
            item {
                StudioEmptyState(
                    icon = Icons.Default.Search,
                    title = "No Matching Projects",
                    description = "No projects match \"$searchQuery\".",
                    primaryActionLabel = "Clear Search",
                    onPrimaryAction = { viewModel.setProjectSearchQuery("") }
                )
            }
        } else {
            items(filteredProjects, key = { it.id }) { proj ->
                val projEps = allEpisodes.filter { it.projectId == proj.id }.sortedBy { it.episodeNumber }
                val isExpanded = expandedProjectIds.contains(proj.id) || projEps.any { it.id == activeEpisodeId }

                StudioCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = proj.title,
                                style = MaterialTheme.typography.titleMedium,
                                color = StudioTextPrimary
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = proj.animeSeriesName,
                                style = MaterialTheme.typography.bodySmall,
                                color = StudioViolet
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "${proj.sourceLanguage.substringBefore(' ')} → ${proj.targetLanguage.substringBefore(' ')} • ${proj.masterSampleRateHz / 1000} kHz • ${projEps.size} ep",
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                                color = StudioTextMuted
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = { projectPendingDelete = proj },
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(Icons.Default.DeleteOutline, contentDescription = "Delete project", tint = StudioTextMuted)
                            }
                            IconButton(
                                onClick = {
                                    expandedProjectIds = if (isExpanded) expandedProjectIds - proj.id else expandedProjectIds + proj.id
                                },
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(
                                    imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                    contentDescription = "Toggle episodes",
                                    tint = StudioTextSecondary
                                )
                            }
                        }
                    }

                    if (isExpanded) {
                        Spacer(modifier = Modifier.height(10.dp))
                        HorizontalDivider(color = StudioBorderSubtle)
                        Spacer(modifier = Modifier.height(10.dp))

                        if (projEps.isEmpty()) {
                            Text(
                                text = "No episodes imported yet.",
                                style = MaterialTheme.typography.bodySmall,
                                color = StudioTextMuted
                            )
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                projEps.forEach { ep ->
                                    val isSelected = ep.id == activeEpisodeId
                                    val doneCount = (1..14).count { (ep.completedStagesMask and (1 shl it)) != 0 }
                                    Surface(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                viewModel.selectActiveEpisode(ep.id, proj.id, navigateToProduction = true)
                                            },
                                        shape = RoundedCornerShape(10.dp),
                                        color = if (isSelected) StudioViolet.copy(alpha = 0.12f) else StudioSurfaceElevated,
                                        border = BorderStroke(
                                            1.dp,
                                            if (isSelected) StudioViolet.copy(alpha = 0.55f) else StudioBorderSubtle
                                        )
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(12.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = "Ep ${ep.episodeNumber} — ${ep.episodeTitle}",
                                                    style = MaterialTheme.typography.labelLarge,
                                                    color = StudioTextPrimary,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Text(
                                                    text = "${ep.videoFileName.ifBlank { "No media" }} • ${formatDurationCompact(ep.durationMs)} • ${formatShortDate(ep.updatedAt)}",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = StudioTextSecondary,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                            Spacer(modifier = Modifier.width(8.dp))
                                            StatusPill(
                                                text = "$doneCount/14",
                                                color = statusColorFor(ep.pipelineStatus)
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                        OutlinedButton(
                            onClick = { viewModel.openGuidedImportEpisode(proj) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, StudioBorder)
                        ) {
                            Icon(Icons.Default.FileUpload, contentDescription = null, tint = StudioCyan, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Import Episode into Project", color = StudioTextPrimary)
                        }
                    }
                }
            }
        }
    }
}

/**
 * 3. GUIDED 5-STEP NEW PROJECT & MEDIA IMPORT WIZARD
 * Step 1: Project Info
 * Step 2: Episode Creation
 * Step 3: Media File Import (Video or Audio via Android OpenDocument)
 * Step 4: Genuine Media Inspection (Real duration, format, size, resolution, FPS, audio stream, extraction)
 * Step 5: Confirmation & Start Processing
 */
@Composable
fun GuidedProjectImportScreen(viewModel: StudioViewModel) {
    val state by viewModel.guidedImport.collectAsState()

    val mediaPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.inspectMediaUriForImport(uri)
        }
    }

    val stepLabels = listOf("Project", "Episode", "Import", "Inspect", "Confirm")

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .testTag("guided_import_screen")
    ) {
        // Header + Close
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = { viewModel.closeOverlay() },
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = StudioTextPrimary)
                }
                Column {
                    Text(
                        text = if (state.existingProjectId == null) "New Dubbing Project" else "Import Episode",
                        style = MaterialTheme.typography.titleLarge,
                        color = StudioTextPrimary
                    )
                    Text(
                        text = "Step ${state.step} of 5 — ${stepLabels.getOrElse(state.step - 1) { "" }}",
                        style = MaterialTheme.typography.bodySmall,
                        color = StudioTextSecondary
                    )
                }
            }
            StatusPill(text = "${state.step}/5", color = StudioViolet)
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Compact 5-step progress indicator
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            stepLabels.forEachIndexed { idx, label ->
                val stepNum = idx + 1
                val active = stepNum == state.step
                val done = stepNum < state.step
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(
                                when {
                                    done -> StudioEmerald
                                    active -> StudioViolet
                                    else -> StudioBorder
                                }
                            )
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (active) StudioTextPrimary else StudioTextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            when (state.step) {
                1 -> {
                    item {
                        StudioCard {
                            Text("Project Details", style = MaterialTheme.typography.titleMedium, color = StudioTextPrimary)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "Define your anime series and target audio configuration. No fake files or demo lines will be added.",
                                style = MaterialTheme.typography.bodySmall,
                                color = StudioTextSecondary
                            )
                            Spacer(modifier = Modifier.height(14.dp))
                            OutlinedTextField(
                                value = state.projectTitle,
                                onValueChange = { v -> viewModel.updateGuidedImport { it.copy(projectTitle = v) } },
                                label = { Text("Project Title") },
                                placeholder = { Text("e.g., Season 1 Hindi-Hinglish Dub") },
                                modifier = Modifier.fillMaxWidth().testTag("import_project_title_input"),
                                singleLine = true
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            OutlinedTextField(
                                value = state.animeSeriesName,
                                onValueChange = { v -> viewModel.updateGuidedImport { it.copy(animeSeriesName = v) } },
                                label = { Text("Anime Series Name") },
                                placeholder = { Text("Used to scope your Anime Dictionary") },
                                modifier = Modifier.fillMaxWidth().testTag("import_series_name_input"),
                                singleLine = true
                            )
                            Spacer(modifier = Modifier.height(14.dp))
                            Text("Master Sample Rate", style = MaterialTheme.typography.labelMedium, color = StudioTextSecondary)
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(24000, 44100, 48000).forEach { sr ->
                                    FilterChip(
                                        selected = state.sampleRateHz == sr,
                                        onClick = { viewModel.updateGuidedImport { it.copy(sampleRateHz = sr) } },
                                        label = { Text("${sr / 1000} kHz") }
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            Text("Preferred Voice Engine Profile", style = MaterialTheme.typography.labelMedium, color = StudioTextSecondary)
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                HinglishTtsNormalizer.AVAILABLE_TTS_ENGINES.forEach { engine ->
                                    FilterChip(
                                        selected = state.preferredTtsEngine == engine.engineId,
                                        onClick = { viewModel.updateGuidedImport { it.copy(preferredTtsEngine = engine.engineId) } },
                                        label = { Text(engine.engineId, style = MaterialTheme.typography.labelSmall) }
                                    )
                                }
                            }
                        }
                    }
                }

                2 -> {
                    item {
                        StudioCard {
                            Text("Episode Setup", style = MaterialTheme.typography.titleMedium, color = StudioTextPrimary)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "Project: ${state.projectTitle.ifBlank { "New Project" }}",
                                style = MaterialTheme.typography.bodySmall,
                                color = StudioViolet
                            )
                            Spacer(modifier = Modifier.height(14.dp))
                            OutlinedTextField(
                                value = state.episodeNumber.toString(),
                                onValueChange = { v ->
                                    val parsed = v.filter { it.isDigit() }.toIntOrNull()
                                    if (parsed != null && parsed > 0) {
                                        viewModel.updateGuidedImport { it.copy(episodeNumber = parsed) }
                                    }
                                },
                                label = { Text("Episode Number") },
                                modifier = Modifier.fillMaxWidth().testTag("import_episode_num_input"),
                                singleLine = true
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            OutlinedTextField(
                                value = state.episodeTitle,
                                onValueChange = { v -> viewModel.updateGuidedImport { it.copy(episodeTitle = v) } },
                                label = { Text("Episode Title") },
                                placeholder = { Text("e.g., Episode ${state.episodeNumber}") },
                                modifier = Modifier.fillMaxWidth().testTag("import_episode_title_input"),
                                singleLine = true
                            )
                        }
                    }
                }

                3 -> {
                    item {
                        StudioCard(padding = 20.dp) {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(64.dp)
                                        .clip(CircleShape)
                                        .background(StudioCyan.copy(alpha = 0.14f))
                                        .border(1.dp, StudioCyan.copy(alpha = 0.4f), CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.FileUpload, contentDescription = null, tint = StudioCyan, modifier = Modifier.size(30.dp))
                                }
                                Spacer(modifier = Modifier.height(14.dp))
                                Text(
                                    text = "Select Source Video or Audio",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = StudioTextPrimary
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "Choose an MP4, MKV, MOV, WAV, M4A, or MP3 file from your device. AnimeDub Studio inspects the real file metadata and extracts a lossless 16-bit PCM audio track.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = StudioTextSecondary
                                )
                                Spacer(modifier = Modifier.height(18.dp))
                                Button(
                                    onClick = { mediaPicker.launch(arrayOf("video/*", "audio/*")) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 52.dp)
                                        .testTag("import_pick_media_btn"),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = StudioViolet,
                                        contentColor = Color(0xFF0A0B0F)
                                    ),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Icon(Icons.Default.AudioFile, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Choose Video or Audio File")
                                }
                            }
                        }
                    }
                }

                4 -> {
                    item {
                        when (state.mediaStatus) {
                            MediaInspectionStatus.LOADING -> {
                                StudioCard(padding = 24.dp) {
                                    Column(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        CircularProgressIndicator(color = StudioViolet, strokeWidth = 3.dp)
                                        Spacer(modifier = Modifier.height(16.dp))
                                        Text("Inspecting Media & Extracting PCM Audio…", style = MaterialTheme.typography.titleSmall, color = StudioTextPrimary)
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            "Reading container streams via MediaExtractor & decoding audio track to 16-bit WAV.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = StudioTextSecondary
                                        )
                                    }
                                }
                            }

                            MediaInspectionStatus.READY -> {
                                val info = state.inspectedMedia!!
                                StudioCard(accentBorder = StudioEmerald.copy(alpha = 0.45f)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = StudioEmerald)
                                            Text("Media Verified", style = MaterialTheme.typography.titleMedium, color = StudioTextPrimary)
                                        }
                                        StatusPill(text = info.formatLabel, color = StudioEmerald)
                                    }
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Text(
                                        text = info.fileName,
                                        style = MaterialTheme.typography.labelLarge.copy(fontFamily = JetBrainsMonoFamily),
                                        color = StudioTextPrimary
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))
                                    MetadataChipRow(
                                        items = listOf(
                                            "Duration" to formatDurationCompact(info.durationMs),
                                            "Size" to formatBytes(info.fileSizeBytes),
                                            "Stream" to info.resolution
                                        )
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    MetadataChipRow(
                                        items = listOf(
                                            "Audio Codec" to info.audioCodec.ifBlank { "PCM" },
                                            "Sample Rate" to "${info.audioSampleRateHz} Hz",
                                            "FPS" to if (info.frameRateFps > 0f) "%.2f".format(info.frameRateFps) else "N/A"
                                        )
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Text("Extracted Master Waveform", style = MaterialTheme.typography.labelSmall, color = StudioTextMuted)
                                    Spacer(modifier = Modifier.height(6.dp))
                                    AudioWaveformCanvas(
                                        audioFilePath = info.extractedWavPath,
                                        accentColor = StudioCyan
                                    )
                                    Spacer(modifier = Modifier.height(10.dp))
                                    OutlinedButton(
                                        onClick = { mediaPicker.launch(arrayOf("video/*", "audio/*")) },
                                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                                        shape = RoundedCornerShape(10.dp)
                                    ) {
                                        Text("Replace File", color = StudioTextPrimary)
                                    }
                                }
                            }

                            MediaInspectionStatus.MISSING_AUDIO,
                            MediaInspectionStatus.UNSUPPORTED,
                            MediaInspectionStatus.EXTRACTION_FAILED -> {
                                val info = state.inspectedMedia
                                StudioCard(accentBorder = StudioCrimson.copy(alpha = 0.55f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = StudioCrimson)
                                        Text(
                                            text = when (state.mediaStatus) {
                                                MediaInspectionStatus.MISSING_AUDIO -> "Missing Audio Track"
                                                MediaInspectionStatus.UNSUPPORTED -> "Unsupported Media Format"
                                                else -> "Audio Extraction Failed"
                                            },
                                            style = MaterialTheme.typography.titleMedium,
                                            color = StudioCrimson
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(8.dp))
                                    if (!info?.fileName.isNullOrBlank()) {
                                        Text(info!!.fileName, style = MaterialTheme.typography.labelMedium, color = StudioTextPrimary)
                                        Spacer(modifier = Modifier.height(4.dp))
                                    }
                                    Text(
                                        text = info?.errorMessage ?: "Unable to extract audio from this file.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = StudioTextSecondary
                                    )
                                    Spacer(modifier = Modifier.height(14.dp))
                                    Button(
                                        onClick = { mediaPicker.launch(arrayOf("video/*", "audio/*")) },
                                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = StudioViolet, contentColor = Color(0xFF0A0B0F)),
                                        shape = RoundedCornerShape(10.dp)
                                    ) {
                                        Text("Select Another File")
                                    }
                                }
                            }

                            MediaInspectionStatus.IDLE -> {
                                StudioEmptyState(
                                    icon = Icons.Default.FileUpload,
                                    title = "No File Selected",
                                    description = "Go back to Step 3 to choose a video or audio file.",
                                    primaryActionLabel = "Choose File",
                                    onPrimaryAction = { mediaPicker.launch(arrayOf("video/*", "audio/*")) }
                                )
                            }
                        }
                    }
                }

                5 -> {
                    val info = state.inspectedMedia
                    item {
                        StudioCard {
                            Text("Confirm & Start Production", style = MaterialTheme.typography.titleMedium, color = StudioTextPrimary)
                            Spacer(modifier = Modifier.height(12.dp))
                            MetadataChipRow(
                                items = listOf(
                                    "Project" to state.projectTitle.ifBlank { "Untitled" },
                                    "Episode" to "Ep ${state.episodeNumber}"
                                )
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            MetadataChipRow(
                                items = listOf(
                                    "Media" to (info?.fileName ?: "None"),
                                    "Duration" to formatDurationCompact(info?.durationMs ?: 0L)
                                )
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "Starting processing now runs Stem Separation (Stage 3), Speech Activity Slicing (Stages 4–5), Audio-Only Speaker Clustering (Stage 6), and Global Dialogue Numbering (Stage 7) on your real audio.",
                                style = MaterialTheme.typography.bodySmall,
                                color = StudioTextSecondary
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(
                                onClick = { viewModel.confirmGuidedImportAndStart(startProcessingNow = true) },
                                enabled = !state.isCommitting && info != null,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 50.dp)
                                    .testTag("import_confirm_start_btn"),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = StudioViolet,
                                    contentColor = Color(0xFF0A0B0F)
                                ),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(if (state.isCommitting) "Creating Workspace…" else "Create & Analyze Audio (Stages 3–7)")
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = { viewModel.confirmGuidedImportAndStart(startProcessingNow = false) },
                                enabled = !state.isCommitting && info != null,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .testTag("import_confirm_save_only_btn"),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("Save Workspace Without Running Pipeline", color = StudioTextPrimary)
                            }
                        }
                    }
                }
            }
        }

        // Bottom Wizard Navigation Bar
        if (state.step < 5) {
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                val minStep = if (state.existingProjectId != null) 2 else 1
                if (state.step > minStep) {
                    OutlinedButton(
                        onClick = { viewModel.updateGuidedImport { it.copy(step = (it.step - 1).coerceAtLeast(minStep)) } },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Back", color = StudioTextPrimary)
                    }
                }

                val canAdvance = when (state.step) {
                    1 -> state.projectTitle.isNotBlank()
                    2 -> state.episodeNumber > 0
                    3 -> state.mediaStatus == MediaInspectionStatus.READY
                    4 -> state.mediaStatus == MediaInspectionStatus.READY
                    else -> false
                }

                Button(
                    onClick = {
                        if (state.step == 3 && state.mediaStatus != MediaInspectionStatus.READY) {
                            mediaPicker.launch(arrayOf("video/*", "audio/*"))
                        } else {
                            viewModel.updateGuidedImport { it.copy(step = (it.step + 1).coerceAtMost(5)) }
                        }
                    },
                    enabled = if (state.step == 3) true else canAdvance,
                    modifier = Modifier
                        .weight(1.4f)
                        .heightIn(min = 48.dp)
                        .testTag("import_next_step_btn"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = StudioViolet,
                        contentColor = Color(0xFF0A0B0F)
                    ),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(if (state.step == 3 && state.mediaStatus != MediaInspectionStatus.READY) "Pick Media" else "Continue")
                    Spacer(modifier = Modifier.width(6.dp))
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}
