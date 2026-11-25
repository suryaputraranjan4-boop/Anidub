package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.local.AgentJobRecordEntity
import com.example.data.local.DialogueSegmentEntity
import com.example.data.local.EpisodeEntity
import com.example.data.local.TranscriptSegmentEntity
import com.example.engine.AudioPlaybackState
import com.example.ui.components.AudioPlayPreviewButton
import com.example.ui.components.AudioWaveformCanvas
import com.example.ui.components.RuntimeCompatibilityBadge
import com.example.ui.components.StudioSectionHeader
import com.example.ui.components.formatTimecodeMs
import com.example.ui.theme.StudioAmber
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

data class SpecialistAgentCardSpec(
    val agentNumber: Int,
    val title: String,
    val primaryStage: Int,
    val modelName: String,
    val runtimeType: String,
    val officialSource: String,
    val description: String
)

val SEVEN_SPECIALIST_AGENT_SPECS = listOf(
    SpecialistAgentCardSpec(
        agentNumber = 6,
        title = "Agent 6 — Background Audio Separation Agent",
        primaryStage = 3,
        modelName = "UVR MDX-Net / Demucs + Residual DSP",
        runtimeType = "ANDROID_LOCAL",
        officialSource = "https://github.com/Anjok07/ultimatevocalremovergui",
        description = "Runs at Stage 3 immediately after audio extraction before speaker analysis. Preserves lossless master, extracts vocal stem & residual background BGM/SFX/ambience stem."
    ),
    SpecialistAgentCardSpec(
        agentNumber = 1,
        title = "Agent 1 — Japanese Transcription Agent",
        primaryStage = 4,
        modelName = "Faster-Whisper Large-v3 (ja-JP)",
        runtimeType = "REMOTE_PYTHON_GPU_WORKER",
        officialSource = "https://huggingface.co/Systran/faster-whisper-large-v3",
        description = "Transcribes spoken Japanese dialogue from the dialogue-focused audio stem while permanently preserving the original Japanese transcript."
    ),
    SpecialistAgentCardSpec(
        agentNumber = 2,
        title = "Agent 2 — Dialogue Timestamp & Segmentation Agent",
        primaryStage = 5,
        modelName = "Silero VAD v5 + WhisperX Japanese Alignment",
        runtimeType = "ONNX_ANDROID",
        officialSource = "https://github.com/snakers4/silero-vad",
        description = "Detects speech boundaries, natural internal pauses, and overlapping dialogue to produce accurate dialogue-level timestamps."
    ),
    SpecialistAgentCardSpec(
        agentNumber = 3,
        title = "Agent 3 — Speaker Identification & Voice Analysis Agent",
        primaryStage = 6,
        modelName = "pyannote community-1 + librosa.pyin + 16-D Embeddings",
        runtimeType = "ANDROID_LOCAL",
        officialSource = "https://huggingface.co/pyannote/speaker-diarization-community-1",
        description = "100% Audio-Only. Zero visual or facial recognition. Extracts F0 pitch range, timbre, spectral centroid, and 16-D embeddings to assign stable A, B, C labels."
    ),
    SpecialistAgentCardSpec(
        agentNumber = 4,
        title = "Agent 4 — Translation & Dictionary Agent",
        primaryStage = 8,
        modelName = "Gemini 3.8 Flash via Firebase AI Logic",
        runtimeType = "EXTERNAL_API",
        officialSource = "https://firebase.google.com/docs/ai-logic",
        description = "Translates Japanese dialogue into natural Roman Hinglish (never Devanagari) enforcing Global, Anime, and Project dictionaries and original duration windows."
    ),
    SpecialistAgentCardSpec(
        agentNumber = 5,
        title = "Agent 5 — Voice Cloning & Generation Agent",
        primaryStage = 10,
        modelName = "Coqui XTTS-v2 / F5-TTS Hindi + Phonetic Normalizer",
        runtimeType = "ANDROID_LOCAL",
        officialSource = "https://huggingface.co/coqui/XTTS-v2",
        description = "Generates Hindi-Hinglish dialogue in CHARACTER-WISE BATCHES (Batch 1: All A → Batch 2: All B → Batch 3: All C) retaining original global dialogue IDs."
    ),
    SpecialistAgentCardSpec(
        agentNumber = 7,
        title = "Agent 7 — Timeline, Mixing & Quality Control Agent",
        primaryStage = 11,
        modelName = "FFmpeg + Native 16-bit PCM WSOLA & 13-Point QC",
        runtimeType = "NATIVE_CPP_DSP",
        officialSource = "https://ffmpeg.org/documentation.html",
        description = "Reconstructs chronological timeline (A-001 → B-002 → C-003...), preserves overlaps, mixes dialogue with background stems, runs 13 QC checks, and exports MP4/WAV."
    )
)

/**
 * Screen 8 — Processing Center (All 7 Agents + 14 Pipeline Stages)
 */
@Composable
fun ProcessingCenterScreen(
    activeEpisode: EpisodeEntity?,
    agentJobs: List<AgentJobRecordEntity>,
    onRunFullPipeline: () -> Unit,
    onRunSingleStage: (Int) -> Unit,
    onInspectAgent: (Int) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            StudioSectionHeader(
                title = "14-Stage Deterministic Processing Center",
                subtitle = "Real stage checkpoints & the 7 logical specialist agents (No fake percentage progress)",
                badgeText = "STAGE ${activeEpisode?.currentStageNumber ?: 1}/14"
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = onRunFullPipeline,
                    colors = ButtonDefaults.buttonColors(containerColor = StudioViolet),
                    modifier = Modifier.weight(1f).testTag("processing_center_run_all_btn")
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Execute Stages 1 → 14")
                }
                OutlinedButton(
                    onClick = {
                        val resumeStage = (activeEpisode?.currentStageNumber ?: 1).coerceIn(1, 14)
                        onRunSingleStage(resumeStage)
                    }
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Resume Checkpoint")
                }
            }
        }

        // Display all 7 Specialist Agents in their exact pipeline execution order
        items(SEVEN_SPECIALIST_AGENT_SPECS) { spec ->
            val latestJob = agentJobs.firstOrNull { it.agentNumber == spec.agentNumber }
            val completedItems = latestJob?.completedItems ?: 0
            val totalItems = (latestJob?.totalItems ?: 1).coerceAtLeast(1)
            val status = latestJob?.status ?: "READY"

            Card(
                colors = CardDefaults.cardColors(containerColor = StudioSurface),
                border = BorderStroke(
                    1.dp,
                    when (status) {
                        "COMPLETED" -> StudioEmerald.copy(alpha = 0.55f)
                        "FAILED" -> StudioCrimson
                        else -> StudioBorder
                    }
                ),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onInspectAgent(spec.agentNumber) }
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = spec.title,
                            style = MaterialTheme.typography.titleMedium,
                            color = StudioTextPrimary,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )
                        RuntimeCompatibilityBadge(spec.runtimeType)
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Model: ${latestJob?.modelBeingUsed ?: spec.modelName}",
                        style = MaterialTheme.typography.labelMedium,
                        color = StudioCyan
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Operation: ${latestJob?.currentOperation ?: spec.description}",
                        style = MaterialTheme.typography.bodySmall,
                        color = StudioTextSecondary
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Input: ${latestJob?.inputSummary ?: "Awaiting stage trigger"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = StudioTextMuted
                    )
                    Text(
                        text = "Output: ${latestJob?.outputSummary ?: "Pending"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = StudioEmerald
                    )
                    if (!latestJob?.errorDetails.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Diagnostic Notice: ${latestJob?.errorDetails}",
                            style = MaterialTheme.typography.labelSmall,
                            color = StudioAmber
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { (completedItems.toFloat() / totalItems.toFloat()).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(5.dp),
                        color = if (status == "COMPLETED") StudioEmerald else StudioViolet,
                        trackColor = StudioSurfaceElevated
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Status: $status ($completedItems / $totalItems units verified)",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (status == "COMPLETED") StudioEmerald else StudioTextSecondary
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { onInspectAgent(spec.agentNumber) }) {
                                Text("Details")
                            }
                            Button(
                                onClick = { onRunSingleStage(spec.primaryStage) },
                                colors = ButtonDefaults.buttonColors(containerColor = StudioViolet)
                            ) {
                                Text("Run Stage ${spec.primaryStage}")
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Screen 9 — Agent Processing Details Inspector
 */
@Composable
fun AgentProcessingDetailsScreen(
    selectedAgentNumber: Int,
    agentJobs: List<AgentJobRecordEntity>,
    onSelectAgentNumber: (Int) -> Unit,
    onRunStage: (Int) -> Unit
) {
    val spec = SEVEN_SPECIALIST_AGENT_SPECS.find { it.agentNumber == selectedAgentNumber }
        ?: SEVEN_SPECIALIST_AGENT_SPECS.first()
    val matchingJobs = agentJobs.filter { it.agentNumber == spec.agentNumber }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            StudioSectionHeader(
                title = "Specialist Agent Inspector",
                subtitle = "Inspect deterministic responsibilities, model bindings, and execution logs",
                badgeText = "AGENT ${spec.agentNumber}"
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(1, 2, 3, 4, 5, 6, 7).forEach { num ->
                    FilterChip(
                        selected = selectedAgentNumber == num,
                        onClick = { onSelectAgentNumber(num) },
                        label = { Text("Agent $num") }
                    )
                }
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = StudioSurface),
                border = BorderStroke(1.dp, StudioViolet),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = spec.title, style = MaterialTheme.typography.headlineSmall, color = StudioTextPrimary, modifier = Modifier.weight(1f))
                        RuntimeCompatibilityBadge(spec.runtimeType)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = "Primary Model: ${spec.modelName}", style = MaterialTheme.typography.titleSmall, color = StudioCyan)
                    Text(text = "Official Source: ${spec.officialSource}", style = MaterialTheme.typography.labelSmall, color = StudioTextMuted)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = spec.description, style = MaterialTheme.typography.bodyMedium, color = StudioTextSecondary)
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = { onRunStage(spec.primaryStage) },
                        colors = ButtonDefaults.buttonColors(containerColor = StudioViolet),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Verified, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Re-Execute ${spec.title} (Stage ${spec.primaryStage})")
                    }
                }
            }
        }

        item {
            StudioSectionHeader(
                title = "Execution Audit Logs for Agent ${spec.agentNumber}",
                subtitle = "${matchingJobs.size} recorded execution checkpoint(s)"
            )
        }

        items(matchingJobs) { job ->
            Card(
                colors = CardDefaults.cardColors(containerColor = StudioSurface),
                border = BorderStroke(1.dp, StudioBorder),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(text = "Stage ${job.stageNumber}: ${job.currentOperation}", style = MaterialTheme.typography.titleSmall, color = StudioTextPrimary)
                        Icon(
                            imageVector = if (job.status == "COMPLETED") Icons.Default.CheckCircle else Icons.Default.Error,
                            contentDescription = job.status,
                            tint = if (job.status == "COMPLETED") StudioEmerald else StudioAmber,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(text = "Model: ${job.modelBeingUsed} (${job.runtimeMode})", style = MaterialTheme.typography.labelSmall, color = StudioCyan)
                    Text(text = "Input: ${job.inputSummary}", style = MaterialTheme.typography.bodySmall, color = StudioTextSecondary)
                    Text(text = "Output: ${job.outputSummary}", style = MaterialTheme.typography.bodySmall, color = StudioEmerald)
                }
            }
        }
    }
}

/**
 * Screen 10 — Agent 1: Japanese Transcript Studio
 */
@Composable
fun JapaneseTranscriptStudioScreen(
    transcripts: List<TranscriptSegmentEntity>,
    playbackState: AudioPlaybackState,
    onPlayAudio: (String, String) -> Unit,
    onStopAudio: () -> Unit,
    onReRunTranscription: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            StudioSectionHeader(
                title = "Agent 1 — Japanese Transcript Studio",
                subtitle = "Original Japanese dialogue transcribed from the separated vocal stem. Permanently preserved and never overwritten by Hindi translation.",
                badgeText = "FASTER-WHISPER LARGE-V3 (JA)"
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = onReRunTranscription,
                colors = ButtonDefaults.buttonColors(containerColor = StudioViolet),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Re-Run Stage 4 Japanese ASR on Vocal Stem")
            }
        }

        items(transcripts) { tr ->
            Card(
                colors = CardDefaults.cardColors(containerColor = StudioSurface),
                border = BorderStroke(1.dp, StudioBorder),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            color = StudioVioletContainer,
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = "JP SEG #%03d  •  %s → %s".format(
                                    tr.segmentIndex,
                                    formatTimecodeMs(tr.preliminaryStartMs),
                                    formatTimecodeMs(tr.preliminaryEndMs)
                                ),
                                style = MaterialTheme.typography.labelMedium,
                                color = StudioViolet,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                        Surface(
                            color = StudioEmeraldContainer,
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = "Conf: ${(tr.confidenceScore * 100).toInt()}%",
                                style = MaterialTheme.typography.labelSmall,
                                color = StudioEmerald,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = tr.japaneseText,
                        style = MaterialTheme.typography.titleMedium,
                        color = StudioTextPrimary,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Romaji: ${tr.romajiReading}",
                        style = MaterialTheme.typography.bodySmall,
                        color = StudioCyan
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    AudioWaveformCanvas(
                        audioFilePath = tr.originalAudioSlicePath,
                        accentColor = StudioViolet,
                        height = 42.dp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = tr.asrModelUsed,
                            style = MaterialTheme.typography.labelSmall,
                            color = StudioTextMuted
                        )
                        AudioPlayPreviewButton(
                            filePath = tr.originalAudioSlicePath,
                            label = "Original JP Audio",
                            playbackState = playbackState,
                            onPlayClick = onPlayAudio,
                            onStopClick = onStopAudio
                        )
                    }
                }
            }
        }
    }
}

/**
 * Screen 11 — Agent 2: Dialogue Segmentation Editor
 */
@Composable
fun DialogueSegmentationEditorScreen(
    dialogues: List<DialogueSegmentEntity>,
    playbackState: AudioPlaybackState,
    onPlayAudio: (String, String) -> Unit,
    onStopAudio: () -> Unit,
    onReRunSegmentation: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            StudioSectionHeader(
                title = "Agent 2 — Dialogue Timestamp & Segmentation Editor",
                subtitle = "Dialogue-level timestamps (not word-level fragments) via Silero VAD v5 + Japanese forced-alignment boundaries",
                badgeText = "SILERO VAD + WHISPERX-JA"
            )
            Spacer(modifier = Modifier.height(6.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = StudioCyanContainer),
                border = BorderStroke(1.dp, StudioCyan),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Global Sequential Dialogue Numbering Active: ${dialogues.joinToString(" → ") { it.dialogueId }}",
                    style = MaterialTheme.typography.labelMedium,
                    color = StudioTextPrimary,
                    modifier = Modifier.padding(12.dp)
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            OutlinedButton(
                onClick = onReRunSegmentation,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Re-Verify Silero VAD & Global Dialogue Numbering (Stages 5 & 7)")
            }
        }

        items(dialogues) { dlg ->
            val spColor = speakerColorFor(dlg.speakerLabel)
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
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                color = spColor.copy(alpha = 0.2f),
                                shape = RoundedCornerShape(6.dp),
                                border = BorderStroke(1.dp, spColor)
                            ) {
                                Text(
                                    text = dlg.dialogueId,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = spColor,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Speaker ${dlg.speakerLabel} • Order #${dlg.globalOrderNumber}",
                                style = MaterialTheme.typography.labelMedium,
                                color = StudioTextSecondary
                            )
                        }
                        Text(
                            text = "Duration: %.3fs".format(dlg.originalDurationMs / 1000.0),
                            style = MaterialTheme.typography.labelMedium,
                            color = StudioCyan
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Start: ${formatTimecodeMs(dlg.originalStartMs)}   →   End: ${formatTimecodeMs(dlg.originalEndMs)}",
                        style = MaterialTheme.typography.labelLarge,
                        color = StudioEmerald
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = dlg.japaneseText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = StudioTextPrimary
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Alignment: ${dlg.alignmentSource}",
                            style = MaterialTheme.typography.labelSmall,
                            color = StudioTextMuted
                        )
                        AudioPlayPreviewButton(
                            filePath = dlg.originalAudioSlicePath,
                            label = "Play ${dlg.dialogueId}",
                            playbackState = playbackState,
                            onPlayClick = onPlayAudio,
                            onStopClick = onStopAudio,
                            accentColor = spColor
                        )
                    }
                }
            }
        }
    }
}
