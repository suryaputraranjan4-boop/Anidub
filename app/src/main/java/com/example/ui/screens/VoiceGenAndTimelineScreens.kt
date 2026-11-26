package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.local.DialogueSegmentEntity
import com.example.data.local.TimelineTrackEntity
import com.example.engine.HinglishTtsNormalizer
import com.example.ui.components.ActiveContextBar
import com.example.ui.components.AudioPlayPauseButton
import com.example.ui.components.AudioWaveformCanvas
import com.example.ui.components.MetadataChipRow
import com.example.ui.components.StatusPill
import com.example.ui.components.StudioCard
import com.example.ui.components.StudioEmptyState
import com.example.ui.components.StudioSectionHeader
import com.example.ui.components.WarningBanner
import com.example.ui.components.formatShortDate
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
import com.example.ui.theme.TrackMusic
import com.example.ui.theme.TrackOriginal
import com.example.ui.theme.TrackAmbience
import com.example.viewmodel.MainTab
import com.example.viewmodel.ProductionSection
import com.example.viewmodel.StudioViewModel
import kotlinx.coroutines.launch
import java.io.File

/**
 * VOICE GENERATION STUDIO (AGENT 5)
 * Displays honest TTS engine availability, character-wise batch queue (All A -> All B -> All C),
 * authorized voice reference status, measured duration & ms deviation, real playback, and history.
 */
@Composable
fun VoiceGenerationView(viewModel: StudioViewModel) {
    val batchDialogues by viewModel.dialoguesCharacterWiseBatches.collectAsState()
    val speakers by viewModel.speakers.collectAsState()
    val history by viewModel.voiceGenerationRecords.collectAsState()
    val config by viewModel.studioConfig.collectAsState()
    val playback by viewModel.playbackState.collectAsState()
    val banner by viewModel.pipelineBanner.collectAsState()
    val scope = rememberCoroutineScope()

    val speakersByLabel = remember(speakers) { speakers.associateBy { it.speakerLabel } }
    val groupedBatches = remember(batchDialogues) {
        batchDialogues.groupBy { it.speakerLabel }.toSortedMap()
    }
    val selectedEngine = HinglishTtsNormalizer.AVAILABLE_TTS_ENGINES.find {
        it.engineId == config.selectedTtsEngine && it.isSupportedOnAndroid
    } ?: HinglishTtsNormalizer.AVAILABLE_TTS_ENGINES.first { it.isSupportedOnAndroid }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("voice_gen_list"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Honest Android-Only Voice Engine Status & Capability Matrix
        item {
            StudioCard(elevated = true) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Voice Synthesis Engine", style = MaterialTheme.typography.titleMedium, color = StudioTextPrimary)
                    StatusPill(
                        text = "ANDROID SYSTEM TTS",
                        color = StudioCyan
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Active Local Engine: ${selectedEngine.displayName}. Synthesizes real spoken Hindi-English audio on-device + WSOLA timing synchronization. Clearly labeled as a generic system TTS fallback — never disguised as Chatterbox or F5-Hindi.",
                    style = MaterialTheme.typography.bodySmall,
                    color = StudioTextSecondary
                )
                Spacer(modifier = Modifier.height(10.dp))
                MetadataChipRow(
                    items = listOf(
                        "Voice Cloning" to if (selectedEngine.supportsVoiceCloning) "Yes" else "No",
                        "Ref Audio" to if (selectedEngine.supportsReferenceAudio) "Yes" else "No",
                        "Neural Emotion" to if (selectedEngine.supportsNeuralEmotionControl) "Yes" else "No",
                        "Rate" to "${selectedEngine.outputSampleRateHz / 1000} kHz"
                    )
                )
                Spacer(modifier = Modifier.height(10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    HinglishTtsNormalizer.AVAILABLE_TTS_ENGINES.forEach { eng ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = eng.displayName,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (eng.isSupportedOnAndroid) StudioTextPrimary else StudioTextMuted
                                )
                                Text(
                                    text = if (eng.isSupportedOnAndroid) {
                                        "On-device Android Speech TTS • ${eng.supportedLanguages}"
                                    } else {
                                        eng.statusWarning ?: "Desktop PyTorch model — unsupported on Android"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (eng.isSupportedOnAndroid) StudioTextSecondary else StudioAmber
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            if (eng.isSupportedOnAndroid) {
                                FilterChip(
                                    selected = selectedEngine.engineId == eng.engineId,
                                    onClick = {
                                        scope.launch {
                                            viewModel.prefsRepo.updateModelSelections(
                                                asrVariant = config.selectedAsrVariant,
                                                ttsEngine = eng.engineId,
                                                separationModel = config.selectedSeparationModel
                                            )
                                        }
                                    },
                                    label = { Text("Active", style = MaterialTheme.typography.labelSmall) }
                                )
                            } else {
                                StatusPill(text = "UNSUPPORTED", color = StudioAmber)
                            }
                        }
                    }
                }
                if (!selectedEngine.statusWarning.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    WarningBanner(message = selectedEngine.statusWarning)
                }
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = { viewModel.runPipelineStages(9, 12) },
                    enabled = !banner.isRunning && batchDialogues.any { it.romanHinglishText.isNotBlank() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("voice_gen_run_batches_btn"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = StudioViolet,
                        contentColor = Color(0xFF0A0B0F)
                    ),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.RecordVoiceOver, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Generate Character Batches (A → B → C) & Mix")
                }
            }
        }

        if (batchDialogues.isEmpty()) {
            item {
                StudioEmptyState(
                    icon = Icons.Default.RecordVoiceOver,
                    title = "No Dialogues Queued for Voice Generation",
                    description = "Segment and translate dialogues into Mixed-Script Hindi-English first to populate the character-wise voice batch queue.",
                    primaryActionLabel = "Go to Translation",
                    onPrimaryAction = { viewModel.selectProductionSection(ProductionSection.TRANSLATION) },
                    testTagPrefix = "voice_gen_empty"
                )
            }
        } else {
            item {
                StudioSectionHeader(
                    title = "Character-Wise Batch Queue",
                    subtitle = "Executes all Speaker A lines, then Speaker B, then Speaker C while keeping global IDs"
                )
            }

            groupedBatches.entries.forEachIndexed { batchIdx, (speakerLabel, lines) ->
                val sp = speakersByLabel[speakerLabel]
                val accent = speakerAccentColor(speakerLabel)
                item(key = "batch_header_$speakerLabel") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            StatusPill(text = "Batch ${batchIdx + 1}", color = accent)
                            Text(
                                text = "${sp?.characterName ?: "Speaker $speakerLabel"} (${lines.size} lines)",
                                style = MaterialTheme.typography.titleSmall,
                                color = StudioTextPrimary
                            )
                        }
                        StatusPill(
                            text = if (sp?.rightsConsentConfirmed == true) "Source Ref" else "Generic Ref",
                            color = if (sp?.rightsConsentConfirmed == true) StudioEmerald else StudioAmber
                        )
                    }
                }

                items(lines.sortedBy { it.globalOrderNumber }, key = { it.id }) { dlg ->
                    val hasAudio = !dlg.generatedAudioPath.isNullOrBlank() && File(dlg.generatedAudioPath).exists()
                    StudioCard(padding = 12.dp) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                StatusPill(text = dlg.dialogueId, color = accent)
                                StatusPill(text = dlg.emotion, color = StudioCyan)
                                StatusPill(text = dlg.generationStatus, color = statusColorFor(dlg.generationStatus))
                            }
                            IconButton(
                                onClick = {
                                    if (dlg.romanHinglishText.isNotBlank()) {
                                        viewModel.updateAndRegenerateSingleDialogue(dlg.id, dlg.romanHinglishText, dlg.emotion)
                                    }
                                },
                                enabled = dlg.romanHinglishText.isNotBlank() && !banner.isRunning,
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = "Regenerate ${dlg.dialogueId}", tint = StudioViolet)
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = dlg.romanHinglishText.ifBlank { "Missing Mixed-Script Hindi-English translation" },
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (dlg.romanHinglishText.isNotBlank()) StudioTextPrimary else StudioAmber
                        )

                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            AudioPlayPauseButton(
                                filePath = dlg.generatedAudioPath,
                                label = "${dlg.dialogueId} Dub",
                                playbackState = playback,
                                onTogglePlay = { path, lbl -> viewModel.audioPlayer.togglePlayPause(path, lbl) },
                                accentColor = accent
                            )
                            AudioWaveformCanvas(
                                audioFilePath = dlg.generatedAudioPath,
                                accentColor = accent,
                                modifier = Modifier.weight(1f),
                                barCount = 44
                            )
                        }

                        if (hasAudio) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Target ${dlg.originalDurationMs}ms • Actual ${dlg.actualGeneratedDurationMs}ms • Δ ${if (dlg.durationDeviationMs >= 0) "+" else ""}${dlg.durationDeviationMs}ms • Stretch ${dlg.timeStretchRatio}x • Sync: ${dlg.syncStatus}",
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                                color = StudioTextMuted
                            )
                        }
                        if (!dlg.errorNote.isNullOrBlank()) {
                            Spacer(modifier = Modifier.height(6.dp))
                            WarningBanner(message = dlg.errorNote, isError = true)
                        }
                    }
                }
            }
        }

        // Genuine Generation History
        if (history.isNotEmpty()) {
            item {
                Spacer(modifier = Modifier.height(4.dp))
                StudioSectionHeader(title = "Generation History", subtitle = "${history.size} recorded take(s)")
            }
            items(history.take(6), key = { it.id }) { rec ->
                StudioCard(padding = 12.dp) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${rec.dialogueId} • ${rec.ttsEngine} (${rec.emotionRequested})",
                                style = MaterialTheme.typography.labelMedium,
                                color = StudioTextPrimary
                            )
                            Text(
                                text = "${rec.actualDurationMs}ms / ${rec.targetDurationMs}ms • ${formatShortDate(rec.createdAt)}",
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                                color = StudioTextMuted
                            )
                        }
                        StatusPill(text = rec.status, color = statusColorFor(rec.status))
                    }
                }
            }
        }
    }
}

/**
 * AUDIO STEMS MANAGER (AGENT 6)
 * Shows only real extracted stems (Protected Original Master, Vocal Stem, Residual Background Accompaniment)
 * with honest waveforms, play/pause, and real Mute/Solo/Gain controls.
 */
@Composable
fun AudioStemsView(viewModel: StudioViewModel) {
    val stems by viewModel.audioStems.collectAsState()
    val playback by viewModel.playbackState.collectAsState()
    val banner by viewModel.pipelineBanner.collectAsState()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("audio_stems_list"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            StudioSectionHeader(
                title = "Audio Stems & Separation",
                subtitle = "Protected lossless master + honest 2-stem vocal & background separation",
                trailing = {
                    OutlinedButton(
                        onClick = { viewModel.runPipelineStages(3, 3) },
                        enabled = !banner.isRunning,
                        modifier = Modifier.heightIn(min = 48.dp),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Re-Separate", color = StudioCyan)
                    }
                }
            )
        }

        if (stems.isEmpty()) {
            item {
                StudioEmptyState(
                    icon = Icons.Default.Layers,
                    title = "No Audio Stems Separated Yet",
                    description = "Run Stage 3 Background Audio Separation to isolate the vocal track for speaker analysis while preserving music, footsteps, and ambience.",
                    primaryActionLabel = "Separate Stems (Stage 3)",
                    onPrimaryAction = { if (!banner.isRunning) viewModel.runPipelineStages(3, 3) },
                    testTagPrefix = "stems_empty"
                )
            }
        } else {
            items(stems, key = { it.id }) { stem ->
                val accent = when (stem.stemType) {
                    "ORIGINAL_MASTER" -> TrackOriginal
                    "DIALOGUE_VOCAL" -> StudioViolet
                    "BACKGROUND_ACCOMPANIMENT" -> TrackMusic
                    else -> TrackAmbience
                }

                StudioCard(padding = 14.dp) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stem.displayName, style = MaterialTheme.typography.titleSmall, color = StudioTextPrimary)
                            Text(
                                text = "${stem.separatorModelUsed} • ${stem.sampleRateHz} Hz • Peak ${stem.peakDbfs} dBFS",
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                                color = StudioTextMuted
                            )
                        }
                        if (stem.stemType == "ORIGINAL_MASTER") {
                            StatusPill(text = "PROTECTED", color = StudioEmerald)
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        AudioPlayPauseButton(
                            filePath = stem.filePath,
                            label = stem.displayName,
                            playbackState = playback,
                            onTogglePlay = { path, lbl -> viewModel.audioPlayer.togglePlayPause(path, lbl) },
                            accentColor = accent
                        )
                        AudioWaveformCanvas(
                            audioFilePath = stem.filePath,
                            accentColor = accent,
                            modifier = Modifier.weight(1f),
                            barCount = 52
                        )
                    }

                    if (stem.stemType != "ORIGINAL_MASTER") {
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = stem.isMuted,
                                onClick = { viewModel.updateStemControls(stem, stem.volumeGain, !stem.isMuted, stem.isSolo) },
                                label = { Text("M", fontWeight = FontWeight.Bold) }
                            )
                            FilterChip(
                                selected = stem.isSolo,
                                onClick = { viewModel.updateStemControls(stem, stem.volumeGain, stem.isMuted, !stem.isSolo) },
                                label = { Text("S", fontWeight = FontWeight.Bold) }
                            )
                            Slider(
                                value = stem.volumeGain,
                                onValueChange = { viewModel.updateStemControls(stem, it, stem.isMuted, stem.isSolo) },
                                valueRange = 0f..1.5f,
                                modifier = Modifier.weight(1f),
                                colors = SliderDefaults.colors(
                                    thumbColor = accent,
                                    activeTrackColor = accent
                                )
                            )
                            Text(
                                text = "${(stem.volumeGain * 100).toInt()}%",
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                                color = StudioTextSecondary
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * FLAGSHIP MOBILE DAW MULTITRACK TIMELINE SCREEN
 * Responsive across portrait and landscape screens.
 * Features: Transport Play/Pause/Stop + Playhead Scrubber, Zoom, Undo/Redo, Time Ruler,
 * Character Tracks with Global ID blocks, Background & Original Audio Tracks, Real PCM Waveforms,
 * Mute/Solo/Volume controls, and Selected Dialogue Timestamp Inspector.
 */
@Composable
fun MultitrackTimelineScreen(viewModel: StudioViewModel) {
    val activeEpisode by viewModel.activeEpisode.collectAsState()
    val activeProject by viewModel.activeProject.collectAsState()
    val tracks by viewModel.timelineTracks.collectAsState()
    val dialogues by viewModel.dialoguesChronological.collectAsState()
    val stems by viewModel.audioStems.collectAsState()
    val selectedDialogueId by viewModel.selectedDialogueId.collectAsState()
    val playback by viewModel.playbackState.collectAsState()
    val zoom by viewModel.timelineZoom.collectAsState()
    val canUndo by viewModel.canUndo.collectAsState()
    val canRedo by viewModel.canRedo.collectAsState()
    val banner by viewModel.pipelineBanner.collectAsState()

    val ep = activeEpisode
    if (ep == null) {
        Box(modifier = Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
            StudioEmptyState(
                icon = Icons.Default.GraphicEq,
                title = "No Active Timeline",
                description = "Import an anime episode in Projects to arrange dialogue clips, adjust timestamps, and mix multitrack stems.",
                primaryActionLabel = "Create Project & Import Episode",
                onPrimaryAction = { viewModel.openGuidedNewProject() },
                testTagPrefix = "timeline_empty_no_ep"
            )
        }
        return
    }

    val mixedPath = ep.mixedMasterAudioPath?.takeIf { File(it).exists() }
        ?: ep.originalMasterAudioPath.takeIf { File(it).exists() }
    val totalDurMs = ep.durationMs.coerceAtLeast(1000L)
    val selectedDialogue = dialogues.find { it.id == selectedDialogueId } ?: dialogues.firstOrNull()
    val isMasterPlaying = playback.isPlaying && playback.activeFilePath == mixedPath

    val bgStemPath = stems.find { it.stemType == "BACKGROUND_ACCOMPANIMENT" }?.filePath
    val origStemPath = stems.find { it.stemType == "ORIGINAL_MASTER" }?.filePath ?: ep.originalMasterAudioPath
    val sfxStemPath = stems.find { it.stemType == "SFX_AMBIENCE" }?.filePath

    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag("multitrack_timeline_screen")
    ) {
        // Active Context Strip
        Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            ActiveContextBar(
                project = activeProject,
                episode = ep,
                onSwitchProject = { viewModel.selectTab(MainTab.PROJECTS) }
            )
        }

        // DAW Transport Bar + Zoom + Undo/Redo
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            color = StudioSurface,
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, StudioBorderSubtle)
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = {
                                if (!mixedPath.isNullOrBlank()) {
                                    viewModel.audioPlayer.togglePlayPause(
                                        mixedPath,
                                        "Master Mix",
                                        playback.currentPositionMs
                                    )
                                }
                            },
                            enabled = !mixedPath.isNullOrBlank(),
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("timeline_play_pause_btn")
                        ) {
                            Icon(
                                imageVector = if (isMasterPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (isMasterPlaying) "Pause master" else "Play master",
                                tint = StudioViolet
                            )
                        }
                        IconButton(
                            onClick = { viewModel.audioPlayer.stop() },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(Icons.Default.Stop, contentDescription = "Stop playback", tint = StudioTextSecondary)
                        }
                        Text(
                            text = "${formatTimeMs(playback.currentPositionMs)} / ${formatTimeMs(totalDurMs)}",
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontFamily = JetBrainsMonoFamily,
                                fontWeight = FontWeight.SemiBold
                            ),
                            color = StudioTextPrimary
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = { viewModel.undoTimelineEdit() },
                            enabled = canUndo,
                            modifier = Modifier.size(48.dp).testTag("timeline_undo_btn")
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo", tint = if (canUndo) StudioCyan else StudioTextMuted)
                        }
                        IconButton(
                            onClick = { viewModel.redoTimelineEdit() },
                            enabled = canRedo,
                            modifier = Modifier.size(48.dp).testTag("timeline_redo_btn")
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "Redo", tint = if (canRedo) StudioCyan else StudioTextMuted)
                        }
                        IconButton(
                            onClick = { viewModel.setTimelineZoom(zoom - 0.35f) },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(Icons.Default.ZoomOut, contentDescription = "Zoom out", tint = StudioTextSecondary)
                        }
                        IconButton(
                            onClick = { viewModel.setTimelineZoom(zoom + 0.35f) },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(Icons.Default.ZoomIn, contentDescription = "Zoom in", tint = StudioTextSecondary)
                        }
                    }
                }

                // Responsive Playhead Scrubber
                Slider(
                    value = playback.currentPositionMs.toFloat().coerceIn(0f, totalDurMs.toFloat()),
                    onValueChange = { viewModel.audioPlayer.seekTo(it.toLong()) },
                    valueRange = 0f..totalDurMs.toFloat(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(28.dp)
                        .testTag("timeline_scrubber"),
                    colors = SliderDefaults.colors(
                        thumbColor = StudioCyan,
                        activeTrackColor = StudioViolet,
                        inactiveTrackColor = StudioBorder
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        if (tracks.isEmpty()) {
            Box(modifier = Modifier.weight(1f).padding(16.dp), contentAlignment = Alignment.Center) {
                StudioEmptyState(
                    icon = Icons.Default.GraphicEq,
                    title = "Multitrack Timeline Not Built Yet",
                    description = "Run Stage 11 Timeline Reconstruction after speaker identification and voice generation to lay out character dialogue and background audio lanes.",
                    primaryActionLabel = "Build Timeline (Stage 11–12)",
                    onPrimaryAction = { if (!banner.isRunning) viewModel.runPipelineStages(11, 12) },
                    secondaryActionLabel = "Open Production Pipeline",
                    onSecondaryAction = { viewModel.selectProductionSection(ProductionSection.PIPELINE) },
                    testTagPrefix = "timeline_empty_tracks"
                )
            }
            return
        }

        // Responsive Landscape (Side-by-Side Lanes + Inspector) vs Portrait (Stacked)
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            val isLandscapeWide = maxWidth >= 660.dp
            val timelineScroll = rememberScrollState()
            val pxPerSecondDp = (46f * zoom).dp
            val totalSeconds = ((totalDurMs + 999L) / 1000L).coerceAtLeast(10L)
            val canvasWidthDp = pxPerSecondDp * totalSeconds.toInt()
            val playheadFraction = (playback.currentPositionMs.toFloat() / totalDurMs.toFloat()).coerceIn(0f, 1f)

            val lanesBlock: @Composable (Modifier) -> Unit = { mod ->
                Surface(
                    modifier = mod,
                    color = StudioSurface,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, StudioBorderSubtle)
                ) {
                    LazyColumn(
                        contentPadding = PaddingValues(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Time Ruler Row
                        item(key = "ruler") {
                            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Box(modifier = Modifier.width(120.dp)) {
                                    Text(
                                        text = "TRACKS (${tracks.size})",
                                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                                        color = StudioTextMuted
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .horizontalScroll(timelineScroll)
                                ) {
                                    Row(modifier = Modifier.width(canvasWidthDp)) {
                                        val stepSec = if (totalSeconds > 120L) 10 else if (totalSeconds > 40L) 5 else 2
                                        var sec = 0L
                                        while (sec <= totalSeconds) {
                                            Text(
                                                text = formatTimeMs(sec * 1000L).substringBeforeLast('.'),
                                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                                                color = StudioTextMuted,
                                                modifier = Modifier.width(pxPerSecondDp * stepSec)
                                            )
                                            sec += stepSec
                                        }
                                    }
                                }
                            }
                            HorizontalDivider(color = StudioBorderSubtle, modifier = Modifier.padding(top = 6.dp))
                        }

                        // Individual DAW Tracks
                        items(tracks, key = { it.id }) { track ->
                            val trackColor = when (track.trackType) {
                                "CHARACTER" -> speakerAccentColor(track.speakerLabel ?: "A")
                                "BGM_MUSIC" -> TrackMusic
                                "SFX_AMBIENCE" -> TrackAmbience
                                else -> TrackOriginal
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(72.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Left Track Control Header (Mute / Solo / Title)
                                Column(
                                    modifier = Modifier
                                        .width(120.dp)
                                        .fillMaxHeight()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(StudioSurfaceElevated)
                                        .border(1.dp, StudioBorderSubtle, RoundedCornerShape(8.dp))
                                        .padding(6.dp),
                                    verticalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = track.trackTitle,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = StudioTextPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                        MiniDawToggle("M", track.isMuted, StudioCrimson) {
                                            viewModel.updateTimelineTrackControls(track, track.volumeGain, !track.isMuted, track.isSolo)
                                        }
                                        MiniDawToggle("S", track.isSolo, StudioAmber) {
                                            viewModel.updateTimelineTrackControls(track, track.volumeGain, track.isMuted, !track.isSolo)
                                        }
                                        Text(
                                            text = "${(track.volumeGain * 100).toInt()}%",
                                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                                            color = StudioTextMuted
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.width(8.dp))

                                // Right Scrollable Track Lane
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(StudioSurfaceElevated.copy(alpha = 0.55f))
                                        .border(1.dp, StudioBorderSubtle, RoundedCornerShape(8.dp))
                                        .horizontalScroll(timelineScroll)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .width(canvasWidthDp)
                                            .fillMaxHeight()
                                    ) {
                                        if (track.trackType == "CHARACTER") {
                                            val trackDialogues = dialogues.filter { it.speakerLabel == track.speakerLabel }
                                            trackDialogues.forEach { dlg ->
                                                val leftDp = pxPerSecondDp * (dlg.timelineStartMs / 1000f)
                                                val durSec = ((dlg.actualGeneratedDurationMs.takeIf { it > 0L } ?: dlg.originalDurationMs) / 1000f).coerceAtLeast(0.5f)
                                                val blockW = (pxPerSecondDp * durSec).coerceAtLeast(58.dp)
                                                val isSel = selectedDialogue?.id == dlg.id

                                                Surface(
                                                    modifier = Modifier
                                                        .offset(x = leftDp, y = 6.dp)
                                                        .width(blockW)
                                                        .height(60.dp)
                                                        .clickable { viewModel.selectDialogue(dlg.id) }
                                                        .testTag("timeline_clip_${dlg.dialogueId}"),
                                                    shape = RoundedCornerShape(8.dp),
                                                    color = trackColor.copy(alpha = if (isSel) 0.34f else 0.18f),
                                                    border = BorderStroke(
                                                        width = if (isSel) 1.5.dp else 1.dp,
                                                        color = if (isSel) trackColor else trackColor.copy(alpha = 0.55f)
                                                    )
                                                ) {
                                                    Column(
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                                                        verticalArrangement = Arrangement.SpaceBetween
                                                    ) {
                                                        Text(
                                                            text = dlg.dialogueId,
                                                            style = MaterialTheme.typography.labelSmall.copy(
                                                                fontFamily = JetBrainsMonoFamily,
                                                                fontWeight = FontWeight.Bold
                                                            ),
                                                            color = StudioTextPrimary,
                                                            maxLines = 1
                                                        )
                                                        Text(
                                                            text = dlg.romanHinglishText.ifBlank { dlg.japaneseText.ifBlank { "Segment" } },
                                                            style = MaterialTheme.typography.labelSmall,
                                                            color = StudioTextSecondary,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis
                                                        )
                                                    }
                                                }
                                            }
                                        } else {
                                            val stemFile = when (track.trackType) {
                                                "BGM_MUSIC" -> bgStemPath
                                                "SFX_AMBIENCE" -> sfxStemPath
                                                else -> origStemPath
                                            }
                                            AudioWaveformCanvas(
                                                audioFilePath = stemFile,
                                                accentColor = trackColor,
                                                barCount = 96,
                                                playbackProgress = playheadFraction,
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .padding(horizontal = 4.dp, vertical = 14.dp)
                                            )
                                        }

                                        // Playhead vertical line
                                        Box(
                                            modifier = Modifier
                                                .offset(x = canvasWidthDp * playheadFraction)
                                                .width(2.dp)
                                                .fillMaxHeight()
                                                .background(StudioCyan)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            val inspectorBlock: @Composable (Modifier) -> Unit = { mod ->
                SelectedDialogueInspectorCard(
                    dialogue = selectedDialogue,
                    tracks = tracks,
                    playback = playback,
                    viewModel = viewModel,
                    modifier = mod
                )
            }

            if (isLandscapeWide) {
                Row(
                    modifier = Modifier.fillMaxSize().padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    lanesBlock(Modifier.weight(1.5f).fillMaxHeight())
                    inspectorBlock(Modifier.weight(1f).fillMaxHeight())
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxSize().padding(bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    lanesBlock(Modifier.weight(1f).fillMaxWidth())
                    inspectorBlock(Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun MiniDawToggle(
    label: String,
    active: Boolean,
    activeColor: Color,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .size(width = 28.dp, height = 26.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(5.dp),
        color = if (active) activeColor.copy(alpha = 0.25f) else StudioSurface,
        border = BorderStroke(1.dp, if (active) activeColor else StudioBorderSubtle)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = if (active) activeColor else StudioTextMuted
            )
        }
    }
}

@Composable
private fun SelectedDialogueInspectorCard(
    dialogue: DialogueSegmentEntity?,
    tracks: List<TimelineTrackEntity>,
    playback: com.example.engine.AudioPlaybackState,
    viewModel: StudioViewModel,
    modifier: Modifier = Modifier
) {
    StudioCard(modifier = modifier, padding = 12.dp) {
        if (dialogue == null) {
            Text("Clip Inspector", style = MaterialTheme.typography.titleSmall, color = StudioTextPrimary)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "Tap any dialogue clip on the timeline to inspect its audio waveform, adjust start timing, or preview playback.",
                style = MaterialTheme.typography.bodySmall,
                color = StudioTextSecondary
            )
        } else {
            val accent = speakerAccentColor(dialogue.speakerLabel)
            val clipAudio = dialogue.generatedAudioPath?.takeIf { File(it).exists() } ?: dialogue.originalAudioSlicePath
            val matchingTrack = tracks.find { it.speakerLabel == dialogue.speakerLabel }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    StatusPill(text = dialogue.dialogueId, color = accent)
                    Text(
                        text = "${formatTimeMs(dialogue.timelineStartMs)} (Orig ${formatTimeMs(dialogue.originalStartMs)})",
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                        color = StudioTextSecondary
                    )
                }
                AudioPlayPauseButton(
                    filePath = clipAudio,
                    label = dialogue.dialogueId,
                    playbackState = playback,
                    onTogglePlay = { path, lbl -> viewModel.audioPlayer.togglePlayPause(path, lbl) },
                    accentColor = accent
                )
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = dialogue.romanHinglishText.ifBlank { dialogue.japaneseText.ifBlank { "No text" } },
                style = MaterialTheme.typography.bodySmall,
                color = StudioTextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(8.dp))
            AudioWaveformCanvas(audioFilePath = clipAudio, accentColor = accent, barCount = 48)

            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OutlinedButton(
                    onClick = { viewModel.adjustDialogueTimelinePosition(dialogue, -100L) },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 6.dp)
                ) {
                    Text("-100ms", style = MaterialTheme.typography.labelSmall, color = StudioTextPrimary)
                }
                OutlinedButton(
                    onClick = { viewModel.adjustDialogueTimelinePosition(dialogue, 100L) },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 6.dp)
                ) {
                    Text("+100ms", style = MaterialTheme.typography.labelSmall, color = StudioTextPrimary)
                }
                OutlinedButton(
                    onClick = { viewModel.restoreDialogueOriginalTimestamp(dialogue) },
                    enabled = dialogue.timelineStartMs != dialogue.originalStartMs,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 6.dp)
                ) {
                    Icon(Icons.Default.RestartAlt, contentDescription = "Reset start", tint = StudioCyan, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(2.dp))
                    Text("Reset", style = MaterialTheme.typography.labelSmall, color = StudioTextPrimary)
                }
            }

            if (matchingTrack != null) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Track Gain", style = MaterialTheme.typography.labelSmall, color = StudioTextMuted)
                    Slider(
                        value = matchingTrack.volumeGain,
                        onValueChange = { viewModel.updateTimelineTrackControls(matchingTrack, it, matchingTrack.isMuted, matchingTrack.isSolo) },
                        valueRange = 0f..1.5f,
                        modifier = Modifier.weight(1f).height(24.dp),
                        colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent)
                    )
                    Text(
                        text = "${(matchingTrack.volumeGain * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                        color = StudioTextSecondary
                    )
                }
            }
        }
    }
}
