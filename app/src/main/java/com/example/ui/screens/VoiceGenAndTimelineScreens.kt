package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.local.AudioStemEntity
import com.example.data.local.DialogueSegmentEntity
import com.example.data.local.EpisodeEntity
import com.example.data.local.SpeakerProfileEntity
import com.example.data.local.TimelineTrackEntity
import com.example.data.local.VoiceGenerationRecordEntity
import com.example.engine.AudioPlaybackState
import com.example.engine.HinglishTtsNormalizer
import com.example.ui.components.AudioPlayPreviewButton
import com.example.ui.components.AudioWaveformCanvas
import com.example.ui.components.StudioSectionHeader
import com.example.ui.components.formatTimecodeMs
import com.example.ui.theme.StudioAmber
import com.example.ui.theme.StudioAmberContainer
import com.example.ui.theme.StudioBackground
import com.example.ui.theme.StudioBorder
import com.example.ui.theme.StudioCrimson
import com.example.ui.theme.StudioCyan
import com.example.ui.theme.StudioCyanContainer
import com.example.ui.theme.StudioEmerald
import com.example.ui.theme.StudioEmeraldContainer
import com.example.ui.theme.StudioSurface
import com.example.ui.theme.StudioSurfaceElevated
import com.example.ui.theme.StudioTextMuted
import com.example.ui.theme.StudioTextPrimary
import com.example.ui.theme.StudioTextSecondary
import com.example.ui.theme.StudioViolet
import com.example.ui.theme.StudioVioletContainer
import com.example.ui.theme.speakerColorFor

/**
 * Screen 18 — Agent 5: Voice Generation Studio
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VoiceGenerationStudioScreen(
    dialogues: List<DialogueSegmentEntity>,
    speakers: List<SpeakerProfileEntity>,
    generationHistory: List<VoiceGenerationRecordEntity>,
    selectedTtsEngineId: String,
    playbackState: AudioPlaybackState,
    onSelectTtsEngine: (String) -> Unit,
    onPlayAudio: (String, String) -> Unit,
    onStopAudio: () -> Unit,
    onRunAllCharacterBatches: () -> Unit,
    onRegenerateSingleDialogue: (Long, String, String) -> Unit,
    onOpenBatchQueue: () -> Unit
) {
    var selectedSpeakerFilter by remember { mutableStateOf("ALL") }
    val engineDescriptor = HinglishTtsNormalizer.AVAILABLE_TTS_ENGINES.find { it.engineId == selectedTtsEngineId }
        ?: HinglishTtsNormalizer.AVAILABLE_TTS_ENGINES.first()

    val filteredDialogues = dialogues.filter {
        selectedSpeakerFilter == "ALL" || it.speakerLabel == selectedSpeakerFilter
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            StudioSectionHeader(
                title = "Agent 5 — Voice Cloning & Generation Studio",
                subtitle = "Pluggable TTS synthesis with Roman Hinglish pronunciation normalizer & reusable character voice references",
                badgeText = engineDescriptor.engineId
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onRunAllCharacterBatches,
                    colors = ButtonDefaults.buttonColors(containerColor = StudioViolet),
                    modifier = Modifier.weight(1f).testTag("voice_gen_run_batches_btn")
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Run Character Batches (A → B → C)")
                }
                OutlinedButton(onClick = onOpenBatchQueue) {
                    Text("Batch Queue")
                }
            }
        }

        // Pluggable TTS Engine Selector with Honest Licensing & Experimental Status
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = StudioSurface),
                border = BorderStroke(1.dp, StudioBorder),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "PLUGGABLE TTS ENGINE INTERFACE",
                        style = MaterialTheme.typography.labelMedium,
                        color = StudioCyan
                    )
                    HinglishTtsNormalizer.AVAILABLE_TTS_ENGINES.forEach { engine ->
                        val isSelected = engine.engineId == engineDescriptor.engineId
                        Surface(
                            color = if (isSelected) StudioVioletContainer else StudioSurfaceElevated,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, if (isSelected) StudioViolet else StudioBorder),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelectTtsEngine(engine.engineId) }
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = engine.displayName,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = StudioTextPrimary,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (engine.isExperimentalArchived) {
                                        Surface(color = StudioAmberContainer, shape = RoundedCornerShape(6.dp)) {
                                            Text(
                                                text = "ARCHIVED AUG 2026",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = StudioAmber,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                }
                                Text(
                                    text = "License: ${engine.licenseName} • Supported Emotions: ${engine.supportedEmotions.joinToString()}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = StudioTextSecondary
                                )
                                if (!engine.statusWarning.isNullOrBlank()) {
                                    Text(
                                        text = engine.statusWarning,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (engine.isExperimentalArchived) StudioAmber else StudioCyan
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Character Filter
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = selectedSpeakerFilter == "ALL",
                    onClick = { selectedSpeakerFilter = "ALL" },
                    label = { Text("All (${dialogues.size})") }
                )
                speakers.forEach { sp ->
                    FilterChip(
                        selected = selectedSpeakerFilter == sp.speakerLabel,
                        onClick = { selectedSpeakerFilter = sp.speakerLabel },
                        label = { Text("Speaker ${sp.speakerLabel} (${sp.dialogueCount})") }
                    )
                }
            }
        }

        // Individual Dialogue Generation Cards (Exposing ONLY model-supported emotion controls!)
        items(filteredDialogues) { dlg ->
            val spColor = speakerColorFor(dlg.speakerLabel)
            var selectedEmotion by remember(dlg.id, dlg.emotion) { mutableStateOf(dlg.emotion) }

            Card(
                colors = CardDefaults.cardColors(containerColor = StudioSurface),
                border = BorderStroke(1.dp, spColor.copy(alpha = 0.55f)),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${dlg.dialogueId} • Batch #${dlg.characterBatchNumber} (Item #${dlg.orderInCharacterBatch})",
                            style = MaterialTheme.typography.labelLarge,
                            color = spColor,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Ref: ${dlg.voiceReferenceId}",
                            style = MaterialTheme.typography.labelSmall,
                            color = StudioCyan
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Visible Roman Hinglish: \"${dlg.romanHinglishText}\"",
                        style = MaterialTheme.typography.titleSmall,
                        color = StudioTextPrimary
                    )
                    Text(
                        text = "Internal TTS Phonetic Layer: ${dlg.ttsNormalizedText}",
                        style = MaterialTheme.typography.labelSmall,
                        color = StudioCyan
                    )

                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Original Duration: %.3fs (%d ms)".format(dlg.originalDurationMs / 1000.0, dlg.originalDurationMs),
                            style = MaterialTheme.typography.labelSmall,
                            color = StudioTextSecondary
                        )
                        Text(
                            text = "Generated: %.3fs (%d ms, Δ %+d ms)".format(
                                dlg.actualGeneratedDurationMs / 1000.0,
                                dlg.actualGeneratedDurationMs,
                                dlg.durationDeviationMs
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = StudioEmerald
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Supported Emotion Controls for ${engineDescriptor.engineId} (No Fake Unsupported Controls Shown):",
                        style = MaterialTheme.typography.labelSmall,
                        color = StudioTextMuted
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        engineDescriptor.supportedEmotions.forEach { emo ->
                            FilterChip(
                                selected = selectedEmotion == emo,
                                onClick = { selectedEmotion = emo },
                                label = { Text(emo) }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    AudioWaveformCanvas(
                        audioFilePath = dlg.generatedAudioPath,
                        accentColor = spColor,
                        height = 46.dp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AudioPlayPreviewButton(
                            filePath = dlg.generatedAudioPath,
                            label = "Play ${dlg.dialogueId}",
                            playbackState = playbackState,
                            onPlayClick = onPlayAudio,
                            onStopClick = onStopAudio,
                            accentColor = spColor
                        )
                        if (!dlg.previousGeneratedAudioPath.isNullOrBlank()) {
                            AudioPlayPreviewButton(
                                filePath = dlg.previousGeneratedAudioPath,
                                label = "Compare Prev",
                                playbackState = playbackState,
                                onPlayClick = onPlayAudio,
                                onStopClick = onStopAudio,
                                accentColor = StudioAmber
                            )
                        }
                        Spacer(modifier = Modifier.weight(1f))
                        Button(
                            onClick = { onRegenerateSingleDialogue(dlg.id, dlg.romanHinglishText, selectedEmotion) },
                            colors = ButtonDefaults.buttonColors(containerColor = StudioViolet)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Regenerate")
                        }
                    }
                }
            }
        }

        if (generationHistory.isNotEmpty()) {
            item {
                StudioSectionHeader(
                    title = "Voice Generation History (${generationHistory.size} Runs)",
                    subtitle = "Recorded generation passes with engine version, duration & stretch ratio"
                )
            }
            items(generationHistory.take(8)) { rec ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = StudioSurface),
                    border = BorderStroke(1.dp, StudioBorder),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(
                            text = "${rec.dialogueId} (Batch ${rec.characterBatchNumber}.${rec.orderInCharacterBatch}) • ${rec.ttsEngine} • ${rec.status}",
                            style = MaterialTheme.typography.labelMedium,
                            color = StudioCyan
                        )
                        Text(
                            text = "Target: ${rec.targetDurationMs} ms → Actual: ${rec.actualDurationMs} ms (Ratio ${rec.timeStretchRatio}x) • Ref: ${rec.voiceReferenceId}",
                            style = MaterialTheme.typography.labelSmall,
                            color = StudioTextSecondary
                        )
                    }
                }
            }
        }
    }
}

/**
 * Screen 19 — Character-Wise Generation Queue (Mandatory Section 4 & Section 12 Comparison)
 * Shows Character-Wise Generation Batches separately from the Final Chronological Timeline!
 */
@Composable
fun CharacterWiseGenerationQueueScreen(
    dialoguesChronological: List<DialogueSegmentEntity>,
    dialoguesCharacterWise: List<DialogueSegmentEntity>,
    playbackState: AudioPlaybackState,
    onPlayAudio: (String, String) -> Unit,
    onStopAudio: () -> Unit,
    onRunBatchGeneration: () -> Unit,
    onRetrySingleDialogue: (DialogueSegmentEntity) -> Unit,
    onOpenChronologicalTimeline: () -> Unit
) {
    val batchesBySpeaker = dialoguesCharacterWise.groupBy { it.speakerLabel }.toSortedMap()

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            StudioSectionHeader(
                title = "Character-Wise Batch Queue vs. Chronological Timeline",
                subtitle = "Mandatory Rule: Voice synthesis runs character-by-character (All A → All B → All C) while preserving global sequential dialogue IDs",
                badgeText = "BATCH A → B → C"
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onRunBatchGeneration,
                    colors = ButtonDefaults.buttonColors(containerColor = StudioViolet),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Re-Run Character-Wise Batches")
                }
                OutlinedButton(onClick = onOpenChronologicalTimeline) {
                    Text("Open Timeline")
                }
            }
        }

        // 1. Character-Wise Generation Batches View
        batchesBySpeaker.entries.forEachIndexed { index, (speakerLabel, batchList) ->
            val batchNumber = index + 1
            val spColor = speakerColorFor(speakerLabel)
            val sortedBatch = batchList.sortedBy { it.globalOrderNumber }

            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = StudioSurface),
                    border = BorderStroke(1.dp, spColor),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "PROCESSING BATCH $batchNumber — CHARACTER $speakerLabel",
                                style = MaterialTheme.typography.titleMedium,
                                color = spColor,
                                fontWeight = FontWeight.Bold
                            )
                            Surface(color = spColor.copy(alpha = 0.2f), shape = RoundedCornerShape(6.dp)) {
                                Text(
                                    text = sortedBatch.joinToString(", ") { it.dialogueId },
                                    style = MaterialTheme.typography.labelMedium,
                                    color = spColor,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(10.dp))

                        sortedBatch.forEach { dlg ->
                            Surface(
                                color = StudioSurfaceElevated,
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, StudioBorder),
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "${dlg.dialogueId} (Original Time: ${formatTimecodeMs(dlg.originalStartMs)} - ${formatTimecodeMs(dlg.originalEndMs)})",
                                            style = MaterialTheme.typography.labelLarge,
                                            color = StudioTextPrimary,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = "\"${dlg.romanHinglishText}\"",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = StudioTextSecondary
                                        )
                                        Text(
                                            text = "Actual: ${dlg.actualGeneratedDurationMs} ms • Ref: ${dlg.voiceReferenceId} • Status: ${dlg.generationStatus}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = StudioEmerald
                                        )
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        AudioPlayPreviewButton(
                                            filePath = dlg.generatedAudioPath,
                                            label = dlg.dialogueId,
                                            playbackState = playbackState,
                                            onPlayClick = onPlayAudio,
                                            onStopClick = onStopAudio,
                                            accentColor = spColor
                                        )
                                        IconButton(onClick = { onRetrySingleDialogue(dlg) }) {
                                            Icon(Icons.Default.Refresh, contentDescription = "Retry ${dlg.dialogueId}", tint = StudioCyan)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 2. Final Reconstructed Chronological Timeline Order Card
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = StudioEmeraldContainer),
                border = BorderStroke(1.dp, StudioEmerald),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "FINAL RECONSTRUCTED CHRONOLOGICAL TIMELINE (STAGE 11)",
                        style = MaterialTheme.typography.labelLarge,
                        color = StudioEmerald,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = dialoguesChronological.joinToString(" → ") { it.dialogueId },
                        style = MaterialTheme.typography.titleMedium,
                        color = StudioTextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "All generated audio files retain their original global sequential IDs and are restored to their original episode start timestamps.",
                        style = MaterialTheme.typography.bodySmall,
                        color = StudioTextSecondary
                    )
                }
            }
        }
    }
}

/**
 * Screen 20 — Agent 6: Background Audio Studio
 */
@Composable
fun BackgroundAudioStudioScreen(
    stems: List<AudioStemEntity>,
    playbackState: AudioPlaybackState,
    onPlayAudio: (String, String) -> Unit,
    onStopAudio: () -> Unit,
    onReRunSeparation: () -> Unit,
    onOpenStemMixer: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            StudioSectionHeader(
                title = "Agent 6 — Background Audio Separation Studio",
                subtitle = "Executed at Stage 3 immediately after extraction and before speaker identification. Lossless Original Master is permanently protected.",
                badgeText = "UVR MDX-NET / HYBRID"
            )
            Spacer(modifier = Modifier.height(6.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = StudioCyanContainer),
                border = BorderStroke(1.dp, StudioCyan),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Honest Stem Reporting: Displaying the actual tracks produced by the separator (Protected Lossless Original Master + Dialogue/Vocal Stem + Residual Background/Accompaniment Stem preserving BGM, SFX, footsteps, wind, explosions & ambience).",
                    style = MaterialTheme.typography.bodySmall,
                    color = StudioTextPrimary,
                    modifier = Modifier.padding(12.dp)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onReRunSeparation,
                    colors = ButtonDefaults.buttonColors(containerColor = StudioViolet),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Re-Run Stage 3 Stem Separation")
                }
                OutlinedButton(onClick = onOpenStemMixer) {
                    Text("Stem Mixer")
                }
            }
        }

        items(stems) { stem ->
            val accent = when (stem.stemType) {
                "ORIGINAL_MASTER" -> StudioEmerald
                "DIALOGUE_VOCAL" -> StudioViolet
                else -> StudioCyan
            }
            Card(
                colors = CardDefaults.cardColors(containerColor = StudioSurface),
                border = BorderStroke(1.dp, accent.copy(alpha = 0.6f)),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stem.displayName,
                            style = MaterialTheme.typography.titleMedium,
                            color = StudioTextPrimary,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )
                        Surface(color = accent.copy(alpha = 0.16f), shape = RoundedCornerShape(6.dp)) {
                            Text(
                                text = "SDR ${(stem.separationQualityScore * 100).toInt()}%",
                                style = MaterialTheme.typography.labelSmall,
                                color = accent,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Model: ${stem.separatorModelUsed}  •  Peak: ${stem.peakDbfs} dBFS  •  ${stem.sampleRateHz} Hz",
                        style = MaterialTheme.typography.labelSmall,
                        color = StudioTextSecondary
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    AudioWaveformCanvas(
                        audioFilePath = stem.filePath,
                        accentColor = accent,
                        height = 54.dp
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    AudioPlayPreviewButton(
                        filePath = stem.filePath,
                        label = "Preview ${stem.stemType.replace('_', ' ')}",
                        playbackState = playbackState,
                        onPlayClick = onPlayAudio,
                        onStopClick = onStopAudio,
                        accentColor = accent
                    )
                }
            }
        }
    }
}

/**
 * Screen 21 — Audio Stem Manager (Gain, Mute & Solo Controls)
 */
@Composable
fun AudioStemManagerScreen(
    stems: List<AudioStemEntity>,
    playbackState: AudioPlaybackState,
    onPlayAudio: (String, String) -> Unit,
    onStopAudio: () -> Unit,
    onUpdateStemControls: (AudioStemEntity, Float, Boolean, Boolean) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            StudioSectionHeader(
                title = "Audio Stem Mixer & Volume Manager",
                subtitle = "Independent volume faders, Mute/Solo toggles, and dependency-aware Stage 12 re-mixing (Never reruns TTS voice generation)",
                badgeText = "${stems.size} STEMS"
            )
        }

        items(stems) { stem ->
            var sliderGain by remember(stem.id, stem.volumeGain) { mutableStateOf(stem.volumeGain) }
            Card(
                colors = CardDefaults.cardColors(containerColor = StudioSurface),
                border = BorderStroke(1.dp, StudioBorder),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = stem.displayName, style = MaterialTheme.typography.titleSmall, color = StudioTextPrimary)
                            Text(
                                text = "Gain: ${(sliderGain * 100).toInt()}% • Peak: ${stem.peakDbfs} dBFS",
                                style = MaterialTheme.typography.labelSmall,
                                color = StudioCyan
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(
                                selected = stem.isMuted,
                                onClick = {
                                    onUpdateStemControls(stem, sliderGain, !stem.isMuted, stem.isSolo)
                                },
                                label = { Text("MUTE") }
                            )
                            FilterChip(
                                selected = stem.isSolo,
                                onClick = {
                                    onUpdateStemControls(stem, sliderGain, stem.isMuted, !stem.isSolo)
                                },
                                label = { Text("SOLO") }
                            )
                        }
                    }

                    Slider(
                        value = sliderGain,
                        onValueChange = { sliderGain = it },
                        onValueChangeFinished = {
                            onUpdateStemControls(stem, sliderGain, stem.isMuted, stem.isSolo)
                        },
                        valueRange = 0f..1.4f
                    )

                    AudioPlayPreviewButton(
                        filePath = stem.filePath,
                        label = "Solo Preview ${stem.stemType}",
                        playbackState = playbackState,
                        onPlayClick = onPlayAudio,
                        onStopClick = onStopAudio
                    )
                }
            }
        }
    }
}

/**
 * Screen 22 — Agent 7: Multitrack Timeline Editor (Flagship Studio Screen)
 */
@Composable
fun MultitrackTimelineEditorScreen(
    activeEpisode: EpisodeEntity?,
    dialoguesChronological: List<DialogueSegmentEntity>,
    tracks: List<TimelineTrackEntity>,
    stems: List<AudioStemEntity>,
    selectedDialogueId: Long?,
    zoomLevel: Float,
    canUndo: Boolean,
    canRedo: Boolean,
    playbackState: AudioPlaybackState,
    onSelectDialogue: (Long?) -> Unit,
    onZoomChange: (Float) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onNudgeDialogueMs: (DialogueSegmentEntity, Long) -> Unit,
    onRestoreOriginalTimestamp: (DialogueSegmentEntity) -> Unit,
    onUpdateTrackControls: (TimelineTrackEntity, Float, Boolean, Boolean) -> Unit,
    onPlayMasterMix: (String, String) -> Unit,
    onStopAudio: () -> Unit,
    onRegenerateSingleDialogue: (Long, String, String) -> Unit
) {
    val episodeDurationMs = (activeEpisode?.durationMs ?: 32000L).coerceAtLeast(10000L)
    val horizontalScrollState = rememberScrollState()
    val pixelsPerSecondDp = (46f * zoomLevel).coerceIn(24f, 130f)
    val totalTimelineWidthDp = ((episodeDurationMs / 1000f) * pixelsPerSecondDp).dp

    val selectedDialogue = dialoguesChronological.find { it.id == selectedDialogueId }
        ?: dialoguesChronological.firstOrNull()

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Top Transport & Timeline Controls Bar
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = StudioSurface),
                border = BorderStroke(1.dp, StudioViolet.copy(alpha = 0.6f)),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "MULTITRACK TIMELINE STUDIO (CHRONOLOGICAL ORDER)",
                                style = MaterialTheme.typography.labelMedium,
                                color = StudioCyan
                            )
                            Text(
                                text = dialoguesChronological.joinToString(" → ") { it.dialogueId },
                                style = MaterialTheme.typography.labelLarge,
                                color = StudioTextPrimary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            IconButton(
                                onClick = onUndo,
                                enabled = canUndo,
                                modifier = Modifier.testTag("timeline_undo_btn")
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.Undo,
                                    contentDescription = "Undo Timing Edit",
                                    tint = if (canUndo) StudioCyan else StudioTextMuted
                                )
                            }
                            IconButton(
                                onClick = onRedo,
                                enabled = canRedo,
                                modifier = Modifier.testTag("timeline_redo_btn")
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.Redo,
                                    contentDescription = "Redo Timing Edit",
                                    tint = if (canRedo) StudioCyan else StudioTextMuted
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        val mixPath = activeEpisode?.mixedMasterAudioPath ?: activeEpisode?.originalMasterAudioPath
                        val isPlayingMaster = playbackState.isPlaying && playbackState.activeFilePath == mixPath

                        Button(
                            onClick = {
                                if (isPlayingMaster) {
                                    onStopAudio()
                                } else if (!mixPath.isNullOrBlank()) {
                                    onPlayMasterMix(mixPath, "Synchronized Dubbed Master Mix")
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isPlayingMaster) StudioCrimson else StudioEmerald
                            ),
                            modifier = Modifier.testTag("timeline_play_master_btn")
                        ) {
                            Icon(
                                imageVector = if (isPlayingMaster) Icons.Default.Stop else Icons.Default.PlayArrow,
                                contentDescription = "Play Master Timeline"
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                if (isPlayingMaster) {
                                    "Stop (${formatTimecodeMs(playbackState.currentPositionMs)})"
                                } else {
                                    "Play Synchronized Master Mix"
                                }
                            )
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { onZoomChange(zoomLevel - 0.25f) }) {
                                Icon(Icons.Default.ZoomOut, contentDescription = "Zoom Out", tint = StudioTextSecondary)
                            }
                            Text(
                                text = "%.1fx".format(zoomLevel),
                                style = MaterialTheme.typography.labelMedium,
                                color = StudioCyan
                            )
                            IconButton(onClick = { onZoomChange(zoomLevel + 0.25f) }) {
                                Icon(Icons.Default.ZoomIn, contentDescription = "Zoom In", tint = StudioTextSecondary)
                            }
                        }
                    }
                }
            }
        }

        // Scrollable Multi-Track Timeline Canvas & Character Tracks
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0A0B10)),
                border = BorderStroke(1.dp, StudioBorder),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(horizontalScrollState)
                        .padding(12.dp)
                ) {
                    // 1. Horizontal Time Ruler (00:00.000 to 00:32.000)
                    val totalSeconds = (episodeDurationMs / 1000L).toInt()
                    Box(
                        modifier = Modifier
                            .width(totalTimelineWidthDp)
                            .height(28.dp)
                            .background(StudioSurfaceElevated, RoundedCornerShape(6.dp))
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val secWidthPx = size.width / totalSeconds.coerceAtLeast(1)
                            for (sec in 0..totalSeconds) {
                                val x = sec * secWidthPx
                                drawLine(
                                    color = if (sec % 5 == 0) StudioCyan else StudioBorder,
                                    start = Offset(x, if (sec % 5 == 0) 0f else size.height * 0.5f),
                                    end = Offset(x, size.height),
                                    strokeWidth = if (sec % 5 == 0) 2.5f else 1f
                                )
                            }
                        }
                        for (sec in 0..totalSeconds step 2) {
                            Text(
                                text = "%02d:%02d.0".format(sec / 60, sec % 60),
                                style = MaterialTheme.typography.labelSmall,
                                color = StudioTextSecondary,
                                modifier = Modifier
                                    .offset(x = (sec * pixelsPerSecondDp).dp + 3.dp, y = 2.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // 2. Character Dialogue Tracks (Speaker A, Speaker B, Speaker C)
                    val distinctSpeakers = dialoguesChronological.map { it.speakerLabel }.distinct().sorted()
                    distinctSpeakers.forEach { spLabel ->
                        val spColor = speakerColorFor(spLabel)
                        val spDialogues = dialoguesChronological.filter { it.speakerLabel == spLabel }
                        val matchingTrack = tracks.find { it.speakerLabel == spLabel }

                        Column(modifier = Modifier.padding(vertical = 4.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(bottom = 3.dp)
                            ) {
                                Surface(
                                    color = spColor.copy(alpha = 0.22f),
                                    shape = RoundedCornerShape(4.dp)
                                ) {
                                    Text(
                                        text = matchingTrack?.trackTitle ?: "Character Track $spLabel",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = spColor,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                    )
                                }
                                if (matchingTrack != null) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Icon(
                                        imageVector = if (matchingTrack.isMuted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                                        contentDescription = "Mute Track $spLabel",
                                        tint = if (matchingTrack.isMuted) StudioCrimson else StudioTextSecondary,
                                        modifier = Modifier
                                            .size(16.dp)
                                            .clickable {
                                                onUpdateTrackControls(
                                                    matchingTrack,
                                                    matchingTrack.volumeGain,
                                                    !matchingTrack.isMuted,
                                                    matchingTrack.isSolo
                                                )
                                            }
                                    )
                                }
                            }

                            Box(
                                modifier = Modifier
                                    .width(totalTimelineWidthDp)
                                    .height(64.dp)
                                    .background(StudioSurface, RoundedCornerShape(8.dp))
                                    .border(1.dp, StudioBorder, RoundedCornerShape(8.dp))
                            ) {
                                spDialogues.forEach { dlg ->
                                    val startOffsetDp = ((dlg.timelineStartMs / 1000f) * pixelsPerSecondDp).dp
                                    val durMs = if (dlg.actualGeneratedDurationMs > 0) dlg.actualGeneratedDurationMs else dlg.originalDurationMs
                                    val blockWidthDp = (((durMs / 1000f) * pixelsPerSecondDp).coerceAtLeast(52f)).dp
                                    val isSelected = dlg.id == selectedDialogue?.id

                                    // Check overlap with any dialogue on another track
                                    val hasOverlap = dialoguesChronological.any { other ->
                                        other.id != dlg.id &&
                                            dlg.timelineStartMs < (other.timelineStartMs + other.originalDurationMs) &&
                                            (dlg.timelineStartMs + durMs) > other.timelineStartMs
                                    }

                                    Box(
                                        modifier = Modifier
                                            .offset(x = startOffsetDp, y = 4.dp)
                                            .width(blockWidthDp)
                                            .height(56.dp)
                                            .background(
                                                spColor.copy(alpha = if (isSelected) 0.34f else 0.18f),
                                                RoundedCornerShape(8.dp)
                                            )
                                            .border(
                                                width = if (isSelected) 2.dp else 1.dp,
                                                color = if (hasOverlap) StudioAmber else spColor,
                                                shape = RoundedCornerShape(8.dp)
                                            )
                                            .clickable { onSelectDialogue(dlg.id) }
                                            .padding(horizontal = 6.dp, vertical = 4.dp)
                                    ) {
                                        Column {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Text(
                                                    text = dlg.dialogueId,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = spColor,
                                                    fontWeight = FontWeight.Bold
                                                )
                                                Text(
                                                    text = "%.2fs".format(durMs / 1000.0),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = StudioTextSecondary
                                                )
                                            }
                                            Text(
                                                text = dlg.romanHinglishText,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = StudioTextPrimary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            AudioWaveformCanvas(
                                                audioFilePath = dlg.generatedAudioPath,
                                                accentColor = spColor,
                                                height = 18.dp,
                                                barCount = 16
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // 3. Preserved Background Music & SFX/Ambience Stems Tracks
                    val bgStem = stems.find { it.stemType == "BACKGROUND_ACCOMPANIMENT" }
                    Column(modifier = Modifier.padding(vertical = 4.dp)) {
                        Text(
                            text = "Track BGM + SFX + Ambience: Residual Background Accompaniment Stem (Preserved)",
                            style = MaterialTheme.typography.labelSmall,
                            color = StudioCyan
                        )
                        Spacer(modifier = Modifier.height(3.dp))
                        Box(
                            modifier = Modifier
                                .width(totalTimelineWidthDp)
                                .height(46.dp)
                                .background(StudioCyanContainer.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
                                .border(1.dp, StudioCyan.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                                .padding(4.dp)
                        ) {
                            AudioWaveformCanvas(
                                audioFilePath = bgStem?.filePath ?: activeEpisode?.originalMasterAudioPath,
                                accentColor = StudioCyan,
                                height = 38.dp,
                                barCount = 96
                            )
                        }
                    }
                }
            }
        }

        // Selected Dialogue Block Inspector & Manual Timing Correction (Without shifting subsequent dialogues!)
        if (selectedDialogue != null) {
            val spColor = speakerColorFor(selectedDialogue.speakerLabel)
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = StudioSurface),
                    border = BorderStroke(1.dp, spColor),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Selected Block: ${selectedDialogue.dialogueId} (Speaker ${selectedDialogue.speakerLabel})",
                                style = MaterialTheme.typography.titleMedium,
                                color = spColor,
                                fontWeight = FontWeight.Bold
                            )
                            AudioPlayPreviewButton(
                                filePath = selectedDialogue.generatedAudioPath,
                                label = "Preview ${selectedDialogue.dialogueId}",
                                playbackState = playbackState,
                                onPlayClick = onPlayMasterMix,
                                onStopClick = onStopAudio,
                                accentColor = spColor
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Original Timestamp: ${formatTimecodeMs(selectedDialogue.originalStartMs)} → ${formatTimecodeMs(selectedDialogue.originalEndMs)} (%.3fs)".format(
                                selectedDialogue.originalDurationMs / 1000.0
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            color = StudioTextSecondary
                        )
                        Text(
                            text = "Current Timeline Position: ${formatTimecodeMs(selectedDialogue.timelineStartMs)}  •  Actual Generated Duration: %.3fs (%d ms)".format(
                                selectedDialogue.actualGeneratedDurationMs / 1000.0,
                                selectedDialogue.actualGeneratedDurationMs
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            color = StudioEmerald
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Roman Hinglish: \"${selectedDialogue.romanHinglishText}\"",
                            style = MaterialTheme.typography.bodyMedium,
                            color = StudioTextPrimary
                        )
                        Text(
                            text = "Japanese Source: ${selectedDialogue.japaneseText}",
                            style = MaterialTheme.typography.bodySmall,
                            color = StudioTextMuted
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "Manual Timing Correction (Independent Placement — Never shifts subsequent dialogues forward):",
                            style = MaterialTheme.typography.labelSmall,
                            color = StudioCyan
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = { onNudgeDialogueMs(selectedDialogue, -100L) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("-100 ms")
                            }
                            OutlinedButton(
                                onClick = { onNudgeDialogueMs(selectedDialogue, +100L) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("+100 ms")
                            }
                            OutlinedButton(
                                onClick = { onRestoreOriginalTimestamp(selectedDialogue) },
                                modifier = Modifier.weight(1.2f)
                            ) {
                                Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(15.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Reset Orig")
                            }
                            Button(
                                onClick = {
                                    onRegenerateSingleDialogue(
                                        selectedDialogue.id,
                                        selectedDialogue.romanHinglishText,
                                        selectedDialogue.emotion
                                    )
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = StudioViolet)
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(15.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}
