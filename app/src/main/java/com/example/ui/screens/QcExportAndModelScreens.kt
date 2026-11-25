package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.local.AgentJobRecordEntity
import com.example.data.local.EpisodeEntity
import com.example.data.local.ExportRecordEntity
import com.example.data.local.ModelManifestEntity
import com.example.data.local.QualityCheckResultEntity
import com.example.data.preferences.WorkerAndStudioConfig
import com.example.data.remote.RemoteWorkerClient
import com.example.data.remote.WorkerHealthStatus
import com.example.engine.AudioPlaybackState
import com.example.ui.components.AudioPlayPreviewButton
import com.example.ui.components.RuntimeCompatibilityBadge
import com.example.ui.components.StudioSectionHeader
import com.example.ui.theme.StudioAmber
import com.example.ui.theme.StudioAmberContainer
import com.example.ui.theme.StudioBorder
import com.example.ui.theme.StudioCrimson
import com.example.ui.theme.StudioCrimsonContainer
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
import com.example.viewmodel.StudioScreen

/**
 * Screen 23 — Stage 13: 13-Point Automated Quality Control Center
 */
@Composable
fun QualityControlCenterScreen(
    checks: List<QualityCheckResultEntity>,
    onReRunQcAudit: () -> Unit,
    onNavigateToRemediation: (StudioScreen) -> Unit,
    onOpenExportStudio: () -> Unit
) {
    val passedCount = checks.count { it.status == "PASSED" }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            StudioSectionHeader(
                title = "Stage 13 — 13-Point Quality Control Center",
                subtitle = "Verifies translations, audio files, unique IDs, chronological order, character-wise batches, voice references, timestamps, overlaps & clipping",
                badgeText = "$passedCount / ${checks.size.coerceAtLeast(13)} PASSED"
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onReRunQcAudit,
                    colors = ButtonDefaults.buttonColors(containerColor = StudioViolet),
                    modifier = Modifier.weight(1f).testTag("qc_rerun_audit_btn")
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Run 13-Point QC Audit")
                }
                Button(
                    onClick = onOpenExportStudio,
                    colors = ButtonDefaults.buttonColors(containerColor = StudioEmerald)
                ) {
                    Text("Stage 14 Export")
                }
            }
        }

        items(checks) { chk ->
            val (accent, container, icon) = when (chk.status) {
                "PASSED" -> Triple(StudioEmerald, StudioEmeraldContainer, Icons.Default.CheckCircle)
                "WARNING" -> Triple(StudioAmber, StudioAmberContainer, Icons.Default.Warning)
                else -> Triple(StudioCrimson, StudioCrimsonContainer, Icons.Default.Error)
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = StudioSurface),
                border = BorderStroke(1.dp, accent.copy(alpha = 0.6f)),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Icon(icon, contentDescription = chk.status, tint = accent, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = chk.checkName,
                                style = MaterialTheme.typography.titleSmall,
                                color = StudioTextPrimary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Surface(color = container, shape = RoundedCornerShape(6.dp)) {
                            Text(
                                text = chk.status,
                                style = MaterialTheme.typography.labelSmall,
                                color = accent,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = chk.summaryMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = StudioTextSecondary
                    )
                    if (chk.affectedDialogueIds.isNotBlank()) {
                        Text(
                            text = "Affected Dialogues: ${chk.affectedDialogueIds}",
                            style = MaterialTheme.typography.labelSmall,
                            color = StudioAmber
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Category: ${chk.category}",
                            style = MaterialTheme.typography.labelSmall,
                            color = StudioTextMuted
                        )
                        Text(
                            text = "Inspect Workstation →",
                            style = MaterialTheme.typography.labelSmall,
                            color = StudioCyan,
                            modifier = Modifier.clickable {
                                val target = when (chk.remediationTargetScreen) {
                                    "TranslationStudio" -> StudioScreen.TRANSLATION_STUDIO
                                    "VoiceGenerationStudio" -> StudioScreen.VOICE_GENERATION_STUDIO
                                    "DialogueSegmentation" -> StudioScreen.DIALOGUE_SEGMENTATION_EDITOR
                                    "CharacterBatchQueue" -> StudioScreen.CHARACTER_WISE_GENERATION_QUEUE
                                    "CharacterVoiceProfiles" -> StudioScreen.CHARACTER_VOICE_PROFILES
                                    "BackgroundAudioStudio" -> StudioScreen.BACKGROUND_AUDIO_STUDIO
                                    "AudioStemManager" -> StudioScreen.AUDIO_STEM_MANAGER
                                    "FinalExportStudio" -> StudioScreen.FINAL_EXPORT_STUDIO
                                    else -> StudioScreen.MULTITRACK_TIMELINE_EDITOR
                                }
                                onNavigateToRemediation(target)
                            }
                        )
                    }
                }
            }
        }
    }
}

/**
 * Screen 24 — Stage 14: Final Export Studio
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FinalExportStudioScreen(
    activeEpisode: EpisodeEntity?,
    exports: List<ExportRecordEntity>,
    playbackState: AudioPlaybackState,
    onPlayAudio: (String, String) -> Unit,
    onStopAudio: () -> Unit,
    onExportEpisode: (
        format: String,
        resolution: String,
        videoBitrateKbps: Int,
        audioCodec: String,
        audioBitrateKbps: Int,
        audioSampleRateHz: Int,
        fileName: String
    ) -> Unit
) {
    var format by remember { mutableStateOf("MP4_VIDEO") }
    var resolution by remember { mutableStateOf("1920x1080 (1080p Full HD)") }
    var videoBitrate by remember { mutableIntStateOf(8500) }
    var audioCodec by remember { mutableStateOf("AAC-LC + Lossless PCM Track") }
    var audioBitrate by remember { mutableIntStateOf(320) }
    var sampleRate by remember { mutableIntStateOf(24000) }
    var customFileName by remember { mutableStateOf("DungeonKing_Ep01_HindiHinglish_Dub") }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            StudioSectionHeader(
                title = "Stage 14 — Final Export Studio",
                subtitle = "Combines original video + synchronized Hindi-Hinglish dialogue + preserved background music, sound effects & ambience",
                badgeText = "MP4 + WAV MASTER"
            )
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = StudioSurface),
                border = BorderStroke(1.dp, StudioViolet),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Output Container Format", style = MaterialTheme.typography.titleSmall, color = StudioTextPrimary)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = format == "MP4_VIDEO",
                            onClick = { format = "MP4_VIDEO" },
                            label = { Text("MP4 Dubbed Video + Audio") }
                        )
                        FilterChip(
                            selected = format == "WAV_AUDIO_ONLY",
                            onClick = { format = "WAV_AUDIO_ONLY" },
                            label = { Text("WAV Master Audio-Only") }
                        )
                    }

                    Text("Video Resolution & Bitrate", style = MaterialTheme.typography.titleSmall, color = StudioTextPrimary)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("1920x1080 (1080p Full HD)", "2560x1440 (1440p QHD)", "1280x720 (720p HD)").forEach { res ->
                            FilterChip(
                                selected = resolution == res,
                                onClick = { resolution = res },
                                label = { Text(res) }
                            )
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(6000, 8500, 14000).forEach { br ->
                            FilterChip(
                                selected = videoBitrate == br,
                                onClick = { videoBitrate = br },
                                label = { Text("${br} kbps Video") }
                            )
                        }
                    }

                    Text("Audio Codec, Bitrate & Sample Rate", style = MaterialTheme.typography.titleSmall, color = StudioTextPrimary)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("AAC-LC + Lossless PCM Track", "PCM 16-bit Uncompressed", "FLAC Lossless").forEach { codec ->
                            FilterChip(
                                selected = audioCodec == codec,
                                onClick = { audioCodec = codec },
                                label = { Text(codec) }
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(192, 256, 320).forEach { abr ->
                            FilterChip(
                                selected = audioBitrate == abr,
                                onClick = { audioBitrate = abr },
                                label = { Text("$abr kbps") }
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(24000, 44100, 48000).forEach { sr ->
                            FilterChip(
                                selected = sampleRate == sr,
                                onClick = { sampleRate = sr },
                                label = { Text("${sr / 1000} kHz") }
                            )
                        }
                    }

                    OutlinedTextField(
                        value = customFileName,
                        onValueChange = { customFileName = it },
                        label = { Text("Output File Name (Saved to App Studio Exports Directory)") },
                        modifier = Modifier.fillMaxWidth().testTag("export_filename_input")
                    )

                    Button(
                        onClick = {
                            onExportEpisode(
                                format,
                                resolution,
                                videoBitrate,
                                audioCodec,
                                audioBitrate,
                                sampleRate,
                                customFileName
                            )
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = StudioEmerald),
                        modifier = Modifier.fillMaxWidth().testTag("export_execute_btn")
                    ) {
                        Icon(Icons.Default.Movie, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Render & Export Synchronized Dubbed Episode")
                    }
                }
            }
        }

        if (exports.isNotEmpty()) {
            item {
                StudioSectionHeader(
                    title = "Verified Export History (${exports.size})",
                    subtitle = "Exported dubbed files on device storage"
                )
            }
            items(exports) { exp ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = StudioSurface),
                    border = BorderStroke(1.dp, StudioEmerald.copy(alpha = 0.6f)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = "${exp.exportFormat}  •  ${exp.videoResolution}  •  ${exp.audioSampleRateHz} Hz",
                            style = MaterialTheme.typography.titleSmall,
                            color = StudioTextPrimary,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Path: ${exp.outputFilePath}",
                            style = MaterialTheme.typography.labelSmall,
                            color = StudioCyan
                        )
                        Text(
                            text = "Size: ${exp.fileSizeBytes / 1024} KB  •  Codec: ${exp.audioCodec} (${exp.audioBitrateKbps} kbps)",
                            style = MaterialTheme.typography.bodySmall,
                            color = StudioTextSecondary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        AudioPlayPreviewButton(
                            filePath = exp.outputFilePath,
                            label = "Play Exported Master",
                            playbackState = playbackState,
                            onPlayClick = onPlayAudio,
                            onStopClick = onStopAudio,
                            accentColor = StudioEmerald
                        )
                    }
                }
            }
        }
    }
}

/**
 * Screen 25 — Verified AI Model Manager (Section 8)
 */
@Composable
fun ModelManagerScreen(
    models: List<ModelManifestEntity>,
    storageUsedBytes: Long,
    storageFreeBytes: Long,
    onInspectModel: (String) -> Unit,
    onDownloadModel: (String) -> Unit,
    onDeleteModel: (String) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            StudioSectionHeader(
                title = "Verified AI Model & Dependency Manager",
                subtitle = "Real HTTP downloads with storage pre-check, resumable Range support & SHA-256 verification. Honest runtime compatibility per model.",
                badgeText = "STORAGE: ${storageFreeBytes / (1024 * 1024)} MB FREE"
            )
        }

        items(models) { model ->
            val isInstalled = model.downloadState == "INSTALLED"
            val isDownloading = model.downloadState == "DOWNLOADING" || model.downloadState == "VERIFYING"

            Card(
                colors = CardDefaults.cardColors(containerColor = StudioSurface),
                border = BorderStroke(1.dp, if (isInstalled) StudioEmerald.copy(alpha = 0.6f) else StudioBorder),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onInspectModel(model.modelId) }
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = model.modelName,
                            style = MaterialTheme.typography.titleMedium,
                            color = StudioTextPrimary,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )
                        RuntimeCompatibilityBadge(model.runtimeCompatibility)
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "${model.category}  •  License: ${model.licenseName}",
                        style = MaterialTheme.typography.labelSmall,
                        color = StudioCyan
                    )
                    if (model.isExperimentalArchived) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Surface(color = StudioAmberContainer, shape = RoundedCornerShape(6.dp)) {
                            Text(
                                text = "ARCHIVED RESEARCH (AUG 2026) — EXPERIMENTAL BENCHMARK ONLY",
                                style = MaterialTheme.typography.labelSmall,
                                color = StudioAmber,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = model.purpose,
                        style = MaterialTheme.typography.bodySmall,
                        color = StudioTextSecondary
                    )
                    Text(
                        text = "Source: ${model.sourceUrl}",
                        style = MaterialTheme.typography.labelSmall,
                        color = StudioTextMuted
                    )
                    Text(
                        text = "Status: ${model.downloadState} — ${model.statusNote}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isInstalled) StudioEmerald else StudioAmber
                    )

                    if (isDownloading) {
                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { model.downloadProgressPercent / 100f },
                            modifier = Modifier.fillMaxWidth().height(5.dp),
                            color = StudioCyan
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(onClick = { onInspectModel(model.modelId) }) {
                            Text("Manifest & License")
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (isInstalled && model.expectedSizeBytes > 0L) {
                                OutlinedButton(onClick = { onDeleteModel(model.modelId) }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = StudioCrimson, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Delete")
                                }
                            }
                            if (model.expectedSizeBytes > 0L) {
                                Button(
                                    onClick = {
                                        if (model.isGatedHuggingFace) {
                                            onInspectModel(model.modelId)
                                        } else {
                                            onDownloadModel(model.modelId)
                                        }
                                    },
                                    enabled = !isDownloading,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (isInstalled) StudioSurfaceElevated else StudioViolet
                                    )
                                ) {
                                    Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        when {
                                            model.isGatedHuggingFace && !isInstalled -> "Gated HF Setup"
                                            isInstalled -> "Re-Verify"
                                            else -> "Download & Verify"
                                        }
                                    )
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
 * Screen 26 — Model Download Details & Checksum Inspector
 */
@Composable
fun ModelDownloadDetailsScreen(
    model: ModelManifestEntity?,
    onDownloadWithToken: (String, String?) -> Unit,
    onDeleteModel: (String) -> Unit
) {
    var hfTokenInput by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (model != null) {
            item {
                StudioSectionHeader(
                    title = model.modelName,
                    subtitle = model.category,
                    badgeText = model.downloadState
                )
            }

            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = StudioSurface),
                    border = BorderStroke(1.dp, StudioViolet),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        RuntimeCompatibilityBadge(model.runtimeCompatibility)
                        Text("Official Source Repository / Model Card:", style = MaterialTheme.typography.labelMedium, color = StudioCyan)
                        Text(model.sourceUrl, style = MaterialTheme.typography.bodySmall, color = StudioTextPrimary)

                        Text("Actual Files Needed:", style = MaterialTheme.typography.labelMedium, color = StudioCyan)
                        Text(model.actualFilesNeededJson, style = MaterialTheme.typography.bodySmall, color = StudioTextSecondary)

                        Text("Required Dependencies:", style = MaterialTheme.typography.labelMedium, color = StudioCyan)
                        Text(model.requiredDependencies, style = MaterialTheme.typography.bodySmall, color = StudioTextSecondary)

                        Text("License & Redistribution Terms:", style = MaterialTheme.typography.labelMedium, color = StudioCyan)
                        Text("${model.licenseName} — ${model.licenseNotes}", style = MaterialTheme.typography.bodySmall, color = StudioAmber)

                        if (model.sha256Checksum.isNotBlank()) {
                            Text("Verified SHA-256 Checksum:", style = MaterialTheme.typography.labelMedium, color = StudioEmerald)
                            Text(model.sha256Checksum, style = MaterialTheme.typography.labelSmall, color = StudioTextPrimary)
                            Text("Local Path: ${model.localFilePath} (${model.downloadedBytes} bytes)", style = MaterialTheme.typography.labelSmall, color = StudioTextMuted)
                        }

                        if (model.isGatedHuggingFace) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Surface(
                                color = StudioAmberContainer,
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, StudioAmber)
                            ) {
                                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(
                                        text = "Gated Hugging Face Model Setup Instructions",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = StudioAmber
                                    )
                                    Text(
                                        text = "1. Log into your Hugging Face account.\n2. Accept the model license terms on ${model.sourceUrl}.\n3. Paste your read-only Hugging Face Access Token below (not embedded in app source code).",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = StudioTextPrimary
                                    )
                                    OutlinedTextField(
                                        value = hfTokenInput,
                                        onValueChange = { hfTokenInput = it },
                                        label = { Text("Hugging Face Read Token (hf_...)") },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (model.expectedSizeBytes > 0L) {
                                Button(
                                    onClick = { onDownloadWithToken(model.modelId, hfTokenInput.takeIf { it.isNotBlank() }) },
                                    colors = ButtonDefaults.buttonColors(containerColor = StudioViolet),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.CloudDownload, contentDescription = null)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Download & SHA-256 Verify")
                                }
                            }
                            if (model.downloadState == "INSTALLED" && model.expectedSizeBytes > 0L) {
                                OutlinedButton(onClick = { onDeleteModel(model.modelId) }) {
                                    Text("Delete Local File")
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
 * Screen 27 — Project Processing History
 */
@Composable
fun ProjectProcessingHistoryScreen(
    allHistory: List<AgentJobRecordEntity>
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            StudioSectionHeader(
                title = "Project Processing & Checkpoint Audit History",
                subtitle = "Complete chronological log of all 7 Specialist Agents, checkpoint recoveries & dependency-aware regenerations",
                badgeText = "${allHistory.size} RECORDS"
            )
        }

        items(allHistory) { job ->
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
                        Text(
                            text = "Stage ${job.stageNumber} • ${job.agentName}",
                            style = MaterialTheme.typography.titleSmall,
                            color = StudioTextPrimary,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = job.status,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (job.status == "COMPLETED") StudioEmerald else StudioAmber
                        )
                    }
                    Text(
                        text = job.currentOperation,
                        style = MaterialTheme.typography.bodySmall,
                        color = StudioCyan
                    )
                    Text(
                        text = "Model: ${job.modelBeingUsed} (${job.runtimeMode})",
                        style = MaterialTheme.typography.labelSmall,
                        color = StudioTextSecondary
                    )
                    Text(
                        text = "Input: ${job.inputSummary}  →  Output: ${job.outputSummary}",
                        style = MaterialTheme.typography.labelSmall,
                        color = StudioTextMuted
                    )
                }
            }
        }
    }
}

/**
 * Screen 28 — Settings & Self-Hostable Python/GPU Inference Worker Configuration
 */
@Composable
fun SettingsScreen(
    config: WorkerAndStudioConfig,
    workerHealth: WorkerHealthStatus?,
    onSaveWorkerSettings: (
        enabled: Boolean,
        url: String,
        hasToken: Boolean,
        gpu: Boolean,
        concurrency: Int,
        timeoutSec: Int,
        uploadLimitMb: Int
    ) -> Unit,
    onTestWorkerHealth: (String) -> Unit
) {
    var enabled by remember(config.remoteWorkerEnabled) { mutableStateOf(config.remoteWorkerEnabled) }
    var workerUrl by remember(config.remoteWorkerBaseUrl) { mutableStateOf(config.remoteWorkerBaseUrl) }
    var hasToken by remember(config.remoteWorkerHasAuthToken) { mutableStateOf(config.remoteWorkerHasAuthToken) }
    var gpuAvailable by remember(config.workerGpuAvailable) { mutableStateOf(config.workerGpuAvailable) }
    var concurrency by remember(config.maxProcessingConcurrency) { mutableIntStateOf(config.maxProcessingConcurrency) }
    var timeoutSec by remember(config.requestTimeoutSeconds) { mutableIntStateOf(config.requestTimeoutSeconds) }
    var uploadLimitMb by remember(config.maxUploadLimitMb) { mutableIntStateOf(config.maxUploadLimitMb) }
    var showServerCode by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            StudioSectionHeader(
                title = "Studio & Hybrid Inference Worker Settings",
                subtitle = "Configure optional self-hosted Python/PyTorch/CUDA inference worker for heavy GPU models. Never disguises an offline worker as remote execution.",
                badgeText = "HYBRID ARCHITECTURE"
            )
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = StudioSurface),
                border = BorderStroke(1.dp, StudioViolet),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Enable Remote Python/GPU Inference Worker", style = MaterialTheme.typography.titleSmall, color = StudioTextPrimary)
                            Text("When disabled or offline, Android Local DSP, ONNX VAD & Firebase AI Logic run natively.", style = MaterialTheme.typography.bodySmall, color = StudioTextSecondary)
                        }
                        Switch(checked = enabled, onCheckedChange = { enabled = it })
                    }

                    OutlinedTextField(
                        value = workerUrl,
                        onValueChange = { workerUrl = it },
                        label = { Text("Self-Hosted Worker Endpoint URL (e.g. https://gpu-worker.example.com:8080)") },
                        modifier = Modifier.fillMaxWidth().testTag("settings_worker_url_input")
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("CUDA GPU Acceleration on Worker", style = MaterialTheme.typography.bodyMedium, color = StudioTextPrimary)
                        Switch(checked = gpuAvailable, onCheckedChange = { gpuAvailable = it })
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(1, 2, 4, 8).forEach { c ->
                            FilterChip(
                                selected = concurrency == c,
                                onClick = { concurrency = c },
                                label = { Text("Max Jobs: $c") }
                            )
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                onSaveWorkerSettings(enabled, workerUrl, hasToken, gpuAvailable, concurrency, timeoutSec, uploadLimitMb)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = StudioViolet),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Save Configuration")
                        }
                        OutlinedButton(
                            onClick = { onTestWorkerHealth(workerUrl) }
                        ) {
                            Icon(Icons.Default.Verified, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Ping /v1/health")
                        }
                    }

                    if (workerHealth != null) {
                        Surface(
                            color = if (workerHealth.reachable) StudioEmeraldContainer else StudioAmberContainer,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, if (workerHealth.reachable) StudioEmerald else StudioAmber)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = "Worker Status: ${workerHealth.deviceType}",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = if (workerHealth.reachable) StudioEmerald else StudioAmber
                                )
                                Text(
                                    text = workerHealth.statusMessage,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = StudioTextPrimary
                                )
                            }
                        }
                    }
                }
            }
        }

        // Self-Hostable Python/FastAPI/PyTorch Worker Reference Code
        item {
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
                        Text(
                            text = "Self-Hostable Python/GPU Worker Specification",
                            style = MaterialTheme.typography.titleSmall,
                            color = StudioCyan
                        )
                        OutlinedButton(onClick = { showServerCode = !showServerCode }) {
                            Text(if (showServerCode) "Hide Script" else "Inspect worker_server.py")
                        }
                    }
                    if (showServerCode) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Surface(
                            color = StudioSurfaceElevated,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = RemoteWorkerClient.SELF_HOSTED_WORKER_SPECIFICATION,
                                style = MaterialTheme.typography.labelSmall,
                                color = StudioTextPrimary,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
