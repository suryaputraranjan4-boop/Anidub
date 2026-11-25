package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import com.example.data.local.DialogueSegmentEntity
import com.example.ui.components.ActiveContextBar
import com.example.ui.components.AudioPlayPauseButton
import com.example.ui.components.AudioWaveformCanvas
import com.example.ui.components.StatusPill
import com.example.ui.components.StudioCard
import com.example.ui.components.StudioEmptyState
import com.example.ui.components.StudioSectionHeader
import com.example.ui.components.WarningBanner
import com.example.ui.components.formatElapsedMs
import com.example.ui.components.formatTimeMs
import com.example.ui.components.speakerAccentColor
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
import com.example.viewmodel.ProductionSection
import com.example.viewmodel.StudioViewModel

/**
 * UNIFIED PRODUCTION WORKSPACE
 * Contextual workflow container housing Pipeline, Transcript, Speakers, Translation,
 * Voice Generation, Audio Stems, and QC & Export inside the open Project & Episode.
 */
@Composable
fun ProductionWorkspaceScreen(viewModel: StudioViewModel) {
    val activeEpisode by viewModel.activeEpisode.collectAsState()
    val activeProject by viewModel.activeProject.collectAsState()
    val activeSection by viewModel.productionSection.collectAsState()

    if (activeEpisode == null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            StudioEmptyState(
                icon = Icons.Default.Movie,
                title = "No Episode Open",
                description = "Create a project or open an imported episode from your Project Library to access the 14-stage production pipeline and editing tools.",
                primaryActionLabel = "Create Project & Import Episode",
                onPrimaryAction = { viewModel.openGuidedNewProject() },
                secondaryActionLabel = "Open Project Library",
                onSecondaryAction = { viewModel.selectTab(MainTab.PROJECTS) },
                testTagPrefix = "production_empty"
            )
        }
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Always-visible Project / Episode / Stage Context Bar
        Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            ActiveContextBar(
                project = activeProject,
                episode = activeEpisode,
                onSwitchProject = { viewModel.selectTab(MainTab.PROJECTS) }
            )
        }

        // Contextual Production Sub-Navigation Bar (7 focused workflow stations)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ProductionSection.entries.forEach { section ->
                val selected = activeSection == section
                FilterChip(
                    selected = selected,
                    onClick = { viewModel.selectProductionSection(section) },
                    label = {
                        Text(
                            text = section.label,
                            style = MaterialTheme.typography.labelMedium
                        )
                    },
                    modifier = Modifier.testTag("prod_tab_${section.name.lowercase()}"),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = StudioViolet.copy(alpha = 0.2f),
                        selectedLabelColor = StudioViolet,
                        containerColor = StudioSurface,
                        labelColor = StudioTextSecondary
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        enabled = true,
                        selected = selected,
                        borderColor = StudioBorderSubtle,
                        selectedBorderColor = StudioViolet.copy(alpha = 0.6f)
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))
        HorizontalDivider(color = StudioBorderSubtle)

        Box(modifier = Modifier.weight(1f)) {
            when (activeSection) {
                ProductionSection.PIPELINE -> ProductionPipelineView(viewModel)
                ProductionSection.TRANSCRIPT -> TranscriptAndDialogueEditorView(viewModel)
                ProductionSection.SPEAKERS -> SpeakerProfilesView(viewModel)
                ProductionSection.TRANSLATION -> TranslationAndDictionaryView(viewModel)
                ProductionSection.VOICE_GEN -> VoiceGenerationView(viewModel)
                ProductionSection.AUDIO_STEMS -> AudioStemsView(viewModel)
                ProductionSection.QC_EXPORT -> QcAndExportView(viewModel)
            }
        }
    }
}

private data class PipelineStageSpec(
    val number: Int,
    val title: String,
    val specialist: String,
    val targetSection: ProductionSection?
)

private val FOURTEEN_STAGES = listOf(
    PipelineStageSpec(1, "Media Import & Inspection", "Coordinator", null),
    PipelineStageSpec(2, "PCM Audio Extraction", "Audio Engine", ProductionSection.AUDIO_STEMS),
    PipelineStageSpec(3, "Background Stem Separation", "Agent 6 • Audio Stems", ProductionSection.AUDIO_STEMS),
    PipelineStageSpec(4, "Speech Slicing & Transcription", "Agent 1 • Japanese ASR", ProductionSection.TRANSCRIPT),
    PipelineStageSpec(5, "Dialogue Segmentation & Overlaps", "Agent 2 • Timestamps", ProductionSection.TRANSCRIPT),
    PipelineStageSpec(6, "Audio-Only Speaker Analysis", "Agent 3 • Voice Profiling", ProductionSection.SPEAKERS),
    PipelineStageSpec(7, "Global Sequential ID Numbering", "Coordinator", ProductionSection.TRANSCRIPT),
    PipelineStageSpec(8, "Roman Hinglish Translation", "Agent 4 • Gemini 3.8 Flash", ProductionSection.TRANSLATION),
    PipelineStageSpec(9, "Voice Reference Preparation", "Agent 5 • Reference Setup", ProductionSection.SPEAKERS),
    PipelineStageSpec(10, "Character-Wise Batch Voice Gen", "Agent 5 • Batch TTS", ProductionSection.VOICE_GEN),
    PipelineStageSpec(11, "Chronological Timeline Restore", "Agent 7 • Timeline", null),
    PipelineStageSpec(12, "Multitrack Stem Mixing", "Agent 7 • Mixer", ProductionSection.AUDIO_STEMS),
    PipelineStageSpec(13, "13-Point Quality Control", "Agent 7 • QC Audit", ProductionSection.QC_EXPORT),
    PipelineStageSpec(14, "Final WAV / MP4 Export", "Agent 7 • Export", ProductionSection.QC_EXPORT)
)

/**
 * 14-STAGE INTERACTIVE PRODUCTION PIPELINE STEPPER
 */
@Composable
fun ProductionPipelineView(viewModel: StudioViewModel) {
    val episode by viewModel.activeEpisode.collectAsState()
    val jobs by viewModel.agentJobsForEpisode.collectAsState()
    val banner by viewModel.pipelineBanner.collectAsState()

    val ep = episode ?: return
    val latestJobByStage = remember(jobs) {
        jobs.groupBy { it.stageNumber }.mapValues { (_, list) -> list.maxByOrNull { it.updatedAt } }
    }
    var expandedStage by remember { mutableStateOf<Int?>(null) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("pipeline_stepper_list"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            StudioCard(elevated = true) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("14-Stage Production Pipeline", style = MaterialTheme.typography.titleMedium, color = StudioTextPrimary)
                        Text(
                            "Run stages individually or execute sequential ranges. No stage is marked complete until verified.",
                            style = MaterialTheme.typography.bodySmall,
                            color = StudioTextSecondary
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { viewModel.runPipelineStages(startStage = 2, endStage = 7) },
                        enabled = !banner.isRunning,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag("pipeline_run_analysis_btn"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = StudioViolet,
                            contentColor = Color(0xFF0A0B0F)
                        ),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Analyze (2–7)", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    OutlinedButton(
                        onClick = { viewModel.runPipelineStages(startStage = 8, endStage = 13) },
                        enabled = !banner.isRunning,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag("pipeline_run_dub_btn"),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, StudioBorder)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, tint = StudioCyan, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Dub & QC (8–13)", color = StudioTextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (banner.isRunning) {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { viewModel.cancelPipeline() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("pipeline_cancel_btn"),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, StudioCrimson.copy(alpha = 0.6f))
                    ) {
                        Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = StudioCrimson, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Cancel Active Pipeline Stage", color = StudioCrimson)
                    }
                }
            }
        }

        if (!ep.lastErrorMessage.isNullOrBlank()) {
            item {
                WarningBanner(message = ep.lastErrorMessage, isError = true)
            }
        }

        items(FOURTEEN_STAGES, key = { it.number }) { stage ->
            val job = latestJobByStage[stage.number]
            val isCompleted = (ep.completedStagesMask and (1 shl stage.number)) != 0
            val isRunning = banner.isRunning && banner.activeStageNumber == stage.number
            val isFailed = job?.status == "FAILED" && !isCompleted
            val prevCompleted = stage.number == 1 || (ep.completedStagesMask and (1 shl (stage.number - 1))) != 0

            val stageStatus = when {
                isRunning -> "RUNNING"
                isFailed -> "FAILED"
                isCompleted -> "COMPLETED"
                prevCompleted -> "READY"
                else -> "WAITING"
            }
            val color = statusColorFor(stageStatus)
            val isExpanded = expandedStage == stage.number

            StudioCard(
                padding = 12.dp,
                accentBorder = when {
                    isRunning -> StudioCyan.copy(alpha = 0.65f)
                    isFailed -> StudioCrimson.copy(alpha = 0.6f)
                    else -> null
                },
                onClick = { expandedStage = if (isExpanded) null else stage.number }
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Stage Number Circle
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(color.copy(alpha = 0.16f))
                            .border(1.dp, color.copy(alpha = 0.5f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isCompleted && !isRunning) {
                            Icon(Icons.Default.Check, contentDescription = "Completed", tint = color, modifier = Modifier.size(16.dp))
                        } else if (isFailed) {
                            Icon(Icons.Default.ErrorOutline, contentDescription = "Failed", tint = color, modifier = Modifier.size(16.dp))
                        } else {
                            Text(
                                text = "%02d".format(stage.number),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = JetBrainsMonoFamily,
                                    fontWeight = FontWeight.Bold
                                ),
                                color = color
                            )
                        }
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stage.title,
                            style = MaterialTheme.typography.titleSmall,
                            color = StudioTextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stage.specialist,
                                style = MaterialTheme.typography.bodySmall,
                                color = StudioTextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (job != null) {
                                Text(
                                    text = "• ${formatElapsedMs(job.startedAt, job.updatedAt)}",
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                                    color = StudioTextMuted
                                )
                            }
                        }
                    }

                    StatusPill(text = stageStatus, color = color)
                    Icon(
                        imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = "Inspect stage",
                        tint = StudioTextMuted,
                        modifier = Modifier.size(18.dp)
                    )
                }

                AnimatedVisibility(visible = isExpanded) {
                    Column(modifier = Modifier.padding(top = 12.dp)) {
                        HorizontalDivider(color = StudioBorderSubtle)
                        Spacer(modifier = Modifier.height(10.dp))
                        if (job != null) {
                            Text("Model / Engine: ${job.modelBeingUsed}", style = MaterialTheme.typography.labelSmall, color = StudioTextMuted)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("Input: ${job.inputSummary}", style = MaterialTheme.typography.bodySmall, color = StudioTextSecondary)
                            Text("Output: ${job.outputSummary}", style = MaterialTheme.typography.bodySmall, color = StudioTextPrimary)
                            if (!job.errorDetails.isNullOrBlank()) {
                                Spacer(modifier = Modifier.height(8.dp))
                                WarningBanner(message = job.errorDetails, isError = true)
                            }
                        } else {
                            Text(
                                text = "Stage has not been executed yet for this episode.",
                                style = MaterialTheme.typography.bodySmall,
                                color = StudioTextMuted
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (stage.number >= 2) {
                                Button(
                                    onClick = { viewModel.runPipelineStages(stage.number, stage.number) },
                                    enabled = !banner.isRunning,
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 48.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (isFailed) StudioCrimson else StudioViolet,
                                        contentColor = Color(0xFF0A0B0F)
                                    ),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Text(if (isFailed) "Retry Stage ${stage.number}" else "Run Stage ${stage.number}")
                                }
                            }
                            if (stage.targetSection != null) {
                                OutlinedButton(
                                    onClick = { viewModel.selectProductionSection(stage.targetSection) },
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 48.dp),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Text("Open ${stage.targetSection.label}", color = StudioTextPrimary)
                                }
                            } else if (stage.number == 11) {
                                OutlinedButton(
                                    onClick = { viewModel.selectTab(MainTab.TIMELINE) },
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 48.dp),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Text("Open Timeline", color = StudioTextPrimary)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * TRANSCRIPT & DIALOGUE SEGMENTATION EDITOR
 * Chronological order preserving the single global sequential numbering rule (A-001, B-002, C-003...).
 * Supports inline Japanese/Romaji/timestamp editing, speaker reassignment, playback, and adding new dialogue slices.
 */
@Composable
fun TranscriptAndDialogueEditorView(viewModel: StudioViewModel) {
    val dialogues by viewModel.dialoguesChronological.collectAsState()
    val transcripts by viewModel.transcripts.collectAsState()
    val speakers by viewModel.speakers.collectAsState()
    val playback by viewModel.playbackState.collectAsState()
    val banner by viewModel.pipelineBanner.collectAsState()

    val transcriptByOrder = remember(transcripts) { transcripts.associateBy { it.segmentIndex } }
    val speakerNames = remember(speakers) { speakers.associate { it.speakerLabel to it.characterName } }
    val availableSpeakerLabels = remember(speakers) {
        (speakers.map { it.speakerLabel } + listOf("A", "B", "C", "D")).distinct().sorted()
    }

    var editingDialogue by remember { mutableStateOf<DialogueSegmentEntity?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }

    if (editingDialogue != null) {
        val dlg = editingDialogue!!
        val matchedTr = transcriptByOrder[dlg.globalOrderNumber]
        var jpText by remember(dlg.id) { mutableStateOf(dlg.japaneseText) }
        var romajiText by remember(dlg.id) { mutableStateOf(matchedTr?.romajiReading.orEmpty()) }
        var startSecStr by remember(dlg.id) { mutableStateOf("%.2f".format(dlg.originalStartMs / 1000.0)) }
        var endSecStr by remember(dlg.id) { mutableStateOf("%.2f".format(dlg.originalEndMs / 1000.0)) }
        var selectedSpeaker by remember(dlg.id) { mutableStateOf(dlg.speakerLabel.ifBlank { "A" }) }

        AlertDialog(
            onDismissRequest = { editingDialogue = null },
            containerColor = StudioSurfaceElevated,
            title = {
                Text("Edit ${dlg.dialogueId}", style = MaterialTheme.typography.titleMedium, color = StudioTextPrimary)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = jpText,
                        onValueChange = { jpText = it },
                        label = { Text("Japanese Dialogue") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2
                    )
                    OutlinedTextField(
                        value = romajiText,
                        onValueChange = { romajiText = it },
                        label = { Text("Romaji Reading (Optional)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = startSecStr,
                            onValueChange = { startSecStr = it },
                            label = { Text("Start (s)") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = endSecStr,
                            onValueChange = { endSecStr = it },
                            label = { Text("End (s)") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                    }
                    Text("Speaker Label", style = MaterialTheme.typography.labelSmall, color = StudioTextSecondary)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        availableSpeakerLabels.take(5).forEach { lbl ->
                            FilterChip(
                                selected = selectedSpeaker == lbl,
                                onClick = { selectedSpeaker = lbl },
                                label = { Text(lbl) }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val sMs = ((startSecStr.toDoubleOrNull() ?: (dlg.originalStartMs / 1000.0)) * 1000.0).toLong()
                        val eMs = ((endSecStr.toDoubleOrNull() ?: (dlg.originalEndMs / 1000.0)) * 1000.0).toLong()
                        viewModel.updateDialogueTranscriptAndTiming(dlg, jpText, romajiText, sMs, eMs)
                        if (selectedSpeaker != dlg.speakerLabel) {
                            viewModel.reassignDialogueSpeaker(dlg, selectedSpeaker)
                        }
                        editingDialogue = null
                    }
                ) {
                    Text("Save", color = StudioViolet)
                }
            },
            dismissButton = {
                TextButton(onClick = { editingDialogue = null }) {
                    Text("Cancel", color = StudioTextSecondary)
                }
            }
        )
    }

    if (showAddDialog) {
        var jpText by remember { mutableStateOf("") }
        var romaji by remember { mutableStateOf("") }
        var startSec by remember { mutableStateOf("0.0") }
        var endSec by remember { mutableStateOf("2.5") }
        var spLabel by remember { mutableStateOf("A") }

        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            containerColor = StudioSurfaceElevated,
            title = { Text("Add Dialogue Segment", color = StudioTextPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = jpText,
                        onValueChange = { jpText = it },
                        label = { Text("Japanese Text") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2
                    )
                    OutlinedTextField(
                        value = romaji,
                        onValueChange = { romaji = it },
                        label = { Text("Romaji (Optional)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = startSec,
                            onValueChange = { startSec = it },
                            label = { Text("Start (s)") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = endSec,
                            onValueChange = { endSec = it },
                            label = { Text("End (s)") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("A", "B", "C", "D").forEach { lbl ->
                            FilterChip(
                                selected = spLabel == lbl,
                                onClick = { spLabel = lbl },
                                label = { Text(lbl) }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val sMs = ((startSec.toDoubleOrNull() ?: 0.0) * 1000.0).toLong()
                        val eMs = ((endSec.toDoubleOrNull() ?: 2.5) * 1000.0).toLong()
                        viewModel.addManualDialogueSegment(jpText, romaji, sMs, eMs, spLabel)
                        showAddDialog = false
                    }
                ) {
                    Text("Add Segment", color = StudioViolet)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) {
                    Text("Cancel", color = StudioTextSecondary)
                }
            }
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("transcript_editor_list"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            StudioSectionHeader(
                title = "Transcript & Dialogue Segments",
                subtitle = "Chronological global IDs • Original Japanese preserved",
                trailing = {
                    OutlinedButton(
                        onClick = { showAddDialog = true },
                        modifier = Modifier.heightIn(min = 48.dp),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, tint = StudioCyan, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Add Line", color = StudioTextPrimary)
                    }
                }
            )
        }

        if (dialogues.isEmpty()) {
            item {
                StudioEmptyState(
                    icon = Icons.Default.Subtitles,
                    title = "No Transcript Segments Yet",
                    description = "Run Stages 3–7 to detect vocal speech windows from your imported audio, or add Japanese dialogue segments manually.",
                    primaryActionLabel = "Detect Speech Windows (Stages 3–7)",
                    onPrimaryAction = { if (!banner.isRunning) viewModel.runPipelineStages(3, 7) },
                    secondaryActionLabel = "Add Segment Manually",
                    onSecondaryAction = { showAddDialog = true },
                    testTagPrefix = "transcript_empty"
                )
            }
        } else {
            val missingJp = dialogues.count { it.japaneseText.isBlank() }
            if (missingJp > 0) {
                item {
                    WarningBanner(
                        message = "$missingJp of ${dialogues.size} speech slice(s) need Japanese transcript text. Listen to each slice and tap Edit to enter Japanese dialogue before running Stage 8 Translation."
                    )
                }
            }

            items(dialogues, key = { it.id }) { dlg ->
                val tr = transcriptByOrder[dlg.globalOrderNumber]
                val accent = speakerAccentColor(dlg.speakerLabel)

                StudioCard(padding = 14.dp) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            StatusPill(text = dlg.dialogueId, color = accent)
                            Text(
                                text = speakerNames[dlg.speakerLabel] ?: "Speaker ${dlg.speakerLabel}",
                                style = MaterialTheme.typography.labelMedium,
                                color = StudioTextPrimary
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "${formatTimeMs(dlg.originalStartMs)} – ${formatTimeMs(dlg.originalEndMs)}",
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                                color = StudioTextMuted
                            )
                            IconButton(
                                onClick = { editingDialogue = dlg },
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(Icons.Default.Edit, contentDescription = "Edit ${dlg.dialogueId}", tint = StudioTextSecondary, modifier = Modifier.size(18.dp))
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    if (dlg.japaneseText.isNotBlank()) {
                        Text(
                            text = dlg.japaneseText,
                            style = MaterialTheme.typography.bodyLarge,
                            color = StudioTextPrimary
                        )
                    } else {
                        Text(
                            text = "No Japanese text entered yet — tap Edit to transcribe this audio slice.",
                            style = MaterialTheme.typography.bodySmall,
                            color = StudioAmber
                        )
                    }

                    if (!tr?.romajiReading.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = tr!!.romajiReading,
                            style = MaterialTheme.typography.bodySmall,
                            color = StudioTextSecondary
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        AudioPlayPauseButton(
                            filePath = dlg.originalAudioSlicePath,
                            label = "${dlg.dialogueId} Original",
                            playbackState = playback,
                            onTogglePlay = { path, lbl -> viewModel.audioPlayer.togglePlayPause(path, lbl) },
                            accentColor = accent
                        )
                        AudioWaveformCanvas(
                            audioFilePath = dlg.originalAudioSlicePath,
                            accentColor = accent,
                            modifier = Modifier.weight(1f),
                            barCount = 44
                        )
                    }

                    if (tr != null && tr.confidenceScore > 0f) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Duration ${dlg.originalDurationMs} ms • Confidence ${(tr.confidenceScore * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                            color = StudioTextMuted
                        )
                    }
                }
            }
        }
    }
}
