package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FactCheck
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Outbox
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.local.ModelManifestEntity
import com.example.data.preferences.GeminiKeySource
import com.example.data.remote.DirectGeminiApiClient
import com.example.engine.HinglishTtsNormalizer
import com.example.engine.ModelDownloadManager
import com.example.engine.VerifiedModelCatalog
import com.example.ui.components.AudioPlayPauseButton
import com.example.ui.components.MetadataChipRow
import com.example.ui.components.StatusPill
import com.example.ui.components.StudioCard
import com.example.ui.components.StudioEmptyState
import com.example.ui.components.StudioSectionHeader
import com.example.ui.components.WarningBanner
import com.example.ui.components.formatBytes
import com.example.ui.components.formatDurationCompact
import com.example.ui.components.formatElapsedMs
import com.example.ui.components.formatShortDate
import com.example.ui.components.statusColorFor
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.StudioAmber
import com.example.ui.theme.StudioBorder
import com.example.ui.theme.StudioBorderSubtle
import com.example.ui.theme.StudioCrimson
import com.example.ui.theme.StudioCyan
import com.example.ui.theme.StudioEmerald
import com.example.ui.theme.StudioTextMuted
import com.example.ui.theme.StudioTextPrimary
import com.example.ui.theme.StudioTextSecondary
import com.example.ui.theme.StudioViolet
import com.example.viewmodel.StudioOverlay
import com.example.viewmodel.StudioViewModel
import kotlinx.coroutines.launch

/**
 * QUALITY CONTROL (13-POINT AUDIT) & FINAL EXPORT STUDIO
 * Explicitly distinguishes Audio-Only WAV vs Muxed Video MP4 and verifies written output files.
 */
@Composable
fun QcAndExportView(viewModel: StudioViewModel) {
    val episode by viewModel.activeEpisode.collectAsState()
    val checks by viewModel.qualityChecks.collectAsState()
    val exports by viewModel.exportsForEpisode.collectAsState()
    val playback by viewModel.playbackState.collectAsState()
    val banner by viewModel.pipelineBanner.collectAsState()

    val ep = episode ?: return
    var exportFormat by remember { mutableStateOf("WAV_AUDIO_ONLY") }
    var sampleRate by remember(ep.id) { mutableStateOf(ep.audioSampleRateHz) }
    var audioBitrate by remember { mutableStateOf(192) }
    var fileName by remember(ep.id) { mutableStateOf("AnimeDub_Ep${ep.episodeNumber}_HindiMixed") }

    val passedCount = checks.count { it.status == "PASSED" }
    val warnCount = checks.count { it.status == "WARNING" }
    val failBlockedCount = checks.count { it.status == "FAILED" || it.status == "BLOCKED" }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("qc_export_list"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 13-Point QC Summary & Run Button
        item {
            StudioCard(elevated = true) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("13-Point Quality Control", style = MaterialTheme.typography.titleMedium, color = StudioTextPrimary)
                        Text(
                            "Verifies translations, dialogue audio files, global IDs, sync, stems, and peak headroom.",
                            style = MaterialTheme.typography.bodySmall,
                            color = StudioTextSecondary
                        )
                    }
                    OutlinedButton(
                        onClick = { viewModel.runPipelineStages(13, 13) },
                        enabled = !banner.isRunning,
                        modifier = Modifier.heightIn(min = 48.dp).testTag("qc_run_audit_btn"),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, tint = StudioCyan, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Run QC", color = StudioTextPrimary)
                    }
                }
                if (checks.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    MetadataChipRow(
                        items = listOf(
                            "Passed" to "$passedCount / 13",
                            "Warnings" to "$warnCount",
                            "Failed / Blocked" to "$failBlockedCount"
                        )
                    )
                }
            }
        }

        if (checks.isEmpty()) {
            item {
                StudioEmptyState(
                    icon = Icons.Default.FactCheck,
                    title = "No Quality Control Audit Yet",
                    description = "Run the 13-point automated audit to inspect translation completeness, audio file verification, synchronization, and peak headroom before exporting.",
                    primaryActionLabel = "Run 13-Point Audit",
                    onPrimaryAction = { if (!banner.isRunning) viewModel.runPipelineStages(13, 13) },
                    testTagPrefix = "qc_empty"
                )
            }
        } else {
            items(checks, key = { it.id }) { qc ->
                val color = statusColorFor(qc.status)
                StudioCard(padding = 12.dp) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(qc.checkName, style = MaterialTheme.typography.labelLarge, color = StudioTextPrimary)
                            Text(qc.category, style = MaterialTheme.typography.labelSmall, color = StudioTextMuted)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        StatusPill(text = qc.status, color = color)
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(qc.summaryMessage, style = MaterialTheme.typography.bodySmall, color = StudioTextSecondary)
                    if (qc.affectedDialogueIds.isNotBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Affected: ${qc.affectedDialogueIds}",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                            color = color
                        )
                    }
                    if (qc.status != "PASSED") {
                        Spacer(modifier = Modifier.height(8.dp))
                        TextButton(
                            onClick = { viewModel.remediateQcTarget(qc.remediationTargetScreen) },
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text("Fix in ${qc.remediationTargetScreen.lowercase().replaceFirstChar { it.uppercase() }} →", color = StudioViolet)
                        }
                    }
                }
            }
        }

        // Stage 14 Final Export Studio
        item {
            Spacer(modifier = Modifier.height(4.dp))
            StudioSectionHeader(
                title = "Final Export Studio",
                subtitle = "Choose Audio-Only WAV master or Muxed Video MP4"
            )
            StudioCard(elevated = true) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    FilterChip(
                        selected = exportFormat == "WAV_AUDIO_ONLY",
                        onClick = { exportFormat = "WAV_AUDIO_ONLY" },
                        label = { Text("Audio-Only WAV (PCM)") },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = exportFormat == "MP4_VIDEO",
                        onClick = { exportFormat = "MP4_VIDEO" },
                        label = { Text("Video MP4 (Passthrough)") },
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = if (exportFormat == "MP4_VIDEO") {
                        "Muxes the original source video stream directly via passthrough (original video codec/bitrate preserved, no re-encoding) with newly encoded AAC-LC dubbed audio into an MP4 container. Requires an imported file with a video stream and passed Stage 13 QC."
                    } else {
                        "Exports a 16-bit PCM WAV master containing synchronized dubbed dialogue and preserved background accompaniment (direct lossless copy when sample rate matches master, or linear-resampled when a different rate is selected). Requires passed Stage 13 QC."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = StudioTextSecondary
                )

                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = fileName,
                    onValueChange = { fileName = it },
                    label = { Text("Output File Name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(24000, 44100, 48000).forEach { sr ->
                        FilterChip(
                            selected = sampleRate == sr,
                            onClick = { sampleRate = sr },
                            label = { Text("${sr / 1000} kHz") }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))
                Button(
                    onClick = {
                        viewModel.exportFinalDubbedEpisode(
                            exportFormat = exportFormat,
                            resolution = ep.resolution,
                            videoBitrateKbps = 0, // Video passthrough: actual source bitrate is probed during muxing
                            audioCodec = if (exportFormat == "MP4_VIDEO") "AAC-LC" else "PCM 16-bit WAV",
                            audioBitrateKbps = audioBitrate,
                            audioSampleRateHz = sampleRate,
                            customFileName = fileName
                        )
                    },
                    enabled = !banner.isRunning,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 50.dp)
                        .testTag("export_execute_btn"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = StudioEmerald,
                        contentColor = Color(0xFF0A0B0F)
                    ),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.Outbox, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(if (exportFormat == "MP4_VIDEO") "Export Verified MP4 + Subtitles" else "Export Verified WAV + Subtitles")
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { viewModel.exportSubtitlesOnly(fileName) },
                    enabled = !banner.isRunning,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("export_subtitles_only_btn"),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, StudioBorder)
                ) {
                    Text("Export Subtitles Only (JA/HI/Bilingual SRT + ASS)", color = StudioCyan)
                }
            }
        }

        // Export History for Episode
        item {
            StudioSectionHeader(title = "Export History", subtitle = "${exports.size} verified file(s)")
        }
        if (exports.isEmpty()) {
            item {
                StudioCard(padding = 14.dp) {
                    Text(
                        text = "No exports created for this episode yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = StudioTextMuted
                    )
                }
            }
        } else {
            items(exports, key = { it.id }) { exp ->
                StudioCard(padding = 12.dp) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = exp.outputFilePath.substringAfterLast('/'),
                                style = MaterialTheme.typography.labelLarge.copy(fontFamily = JetBrainsMonoFamily),
                                color = StudioTextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            val videoSummary = if (exp.exportFormat == "MP4_VIDEO") {
                                if (exp.videoBitrateKbps > 0) " • Passthrough ${exp.videoBitrateKbps} kbps" else " • Video Passthrough"
                            } else ""
                            Text(
                                text = "${exp.exportFormat.replace('_', ' ')}$videoSummary • ${exp.audioCodec} • ${formatBytes(exp.fileSizeBytes)} • ${formatDurationCompact(exp.durationMs)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = StudioTextSecondary
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = formatShortDate(exp.exportedAt),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = StudioTextMuted
                                )
                                StatusPill(text = exp.status, color = statusColorFor(exp.status))
                            }
                            if (exp.subtitlePathsSummary.isNotBlank()) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Subtitles: ${exp.subtitlePathsSummary}",
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                                    color = StudioCyan,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (exp.verificationReport.isNotBlank()) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = exp.verificationReport,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = StudioTextSecondary
                                )
                            }
                        }
                        AudioPlayPauseButton(
                            filePath = exp.outputFilePath,
                            label = "Exported File",
                            playbackState = playback,
                            onTogglePlay = { path, lbl -> viewModel.audioPlayer.togglePlayPause(path, lbl) },
                            accentColor = StudioEmerald
                        )
                    }
                }
            }
        }
    }
}

/**
 * MODEL LIBRARY SCREEN (PRIMARY TAB 5)
 * Android-only runtime audit: separates genuinely supported on-device Android/ONNX models,
 * the Direct Gemini REST API service, and desktop PyTorch models honestly marked UNSUPPORTED_ON_ANDROID.
 */
@Composable
fun ModelLibraryScreen(viewModel: StudioViewModel) {
    val models by viewModel.models.collectAsState()
    val geminiConnectionState by viewModel.geminiConnectionState.collectAsState()

    val onDeviceLocalModels = remember(models) {
        models.filter { ModelDownloadManager.isLocalRuntime(it.runtimeCompatibility) }
    }
    val directGeminiModels = remember(models) {
        models.filter { it.runtimeCompatibility == VerifiedModelCatalog.RUNTIME_DIRECT_GEMINI_API }
    }
    val unsupportedDesktopModels = remember(models) {
        models.filter { it.runtimeCompatibility == VerifiedModelCatalog.RUNTIME_UNSUPPORTED_ON_ANDROID }
    }
    val usedBytes = remember(models) { viewModel.modelDownloadManager.getModelsStorageUsedBytes() }
    val freeBytes = remember(models) { viewModel.modelDownloadManager.getAvailableStorageBytes() }
    val readyLocalCount = remember(onDeviceLocalModels) {
        onDeviceLocalModels.count { it.downloadState in setOf("READY", "BUILT_IN", "INSTALLED") }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("model_library_list"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            StudioSectionHeader(
                title = "Model Assets & Android Runtime",
                subtitle = "Android-only local inference + Direct Gemini API • Zero fake downloads or external workers"
            )
            Spacer(modifier = Modifier.height(6.dp))
            MetadataChipRow(
                items = listOf(
                    "Local Ready" to "$readyLocalCount / ${onDeviceLocalModels.size}",
                    "Gemini API" to geminiConnectionState.pillLabel,
                    "Disk Free" to formatBytes(freeBytes)
                )
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Verified local model storage used: ${formatBytes(usedBytes)} • Desktop PyTorch models are audited honestly below and blocked from fake downloads.",
                style = MaterialTheme.typography.labelSmall,
                color = StudioTextMuted
            )
        }

        // Section 1: On-Device Android & ONNX Runtime Models
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 6.dp)
            ) {
                Icon(Icons.Default.PhoneAndroid, contentDescription = null, tint = StudioEmerald, modifier = Modifier.size(18.dp))
                Text("On-Device Android & ONNX Runtime (${onDeviceLocalModels.size})", style = MaterialTheme.typography.titleMedium, color = StudioTextPrimary)
            }
        }
        items(onDeviceLocalModels, key = { it.modelId }) { model ->
            ModelLibraryCard(
                model = model,
                onOpenDetail = { viewModel.openModelDetail(model.modelId) },
                onDownload = { viewModel.downloadModel(model.modelId) },
                onCancel = { viewModel.cancelModelDownload(model.modelId) },
                onDelete = { viewModel.deleteModel(model.modelId) },
                onOpenSettings = { viewModel.openOverlay(StudioOverlay.SETTINGS) }
            )
        }

        // Section 2: Direct Gemini API (Only Online AI Service)
        if (directGeminiModels.isNotEmpty()) {
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    Icon(Icons.Default.Cloud, contentDescription = null, tint = StudioViolet, modifier = Modifier.size(18.dp))
                    Text("Direct Gemini API (Online Service)", style = MaterialTheme.typography.titleMedium, color = StudioTextPrimary)
                }
            }
            items(directGeminiModels, key = { it.modelId }) { model ->
                ModelLibraryCard(
                    model = model,
                    geminiConnectionState = geminiConnectionState,
                    onOpenDetail = { viewModel.openModelDetail(model.modelId) },
                    onDownload = {},
                    onCancel = {},
                    onDelete = {},
                    onOpenSettings = { viewModel.openOverlay(StudioOverlay.SETTINGS) }
                )
            }
        }

        // Section 3: Desktop PyTorch Models — Unsupported on Android
        if (unsupportedDesktopModels.isNotEmpty()) {
            item {
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.Block, contentDescription = null, tint = StudioAmber, modifier = Modifier.size(18.dp))
                        Text(
                            "Desktop PyTorch Models — Unsupported on Android (${unsupportedDesktopModels.size})",
                            style = MaterialTheme.typography.titleMedium,
                            color = StudioTextPrimary
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Listed transparently so you know exact upstream weights and why they cannot execute locally on Android. Downloads are disabled to prevent wasting GBs of unusable storage.",
                        style = MaterialTheme.typography.labelSmall,
                        color = StudioTextMuted
                    )
                }
            }
            items(unsupportedDesktopModels, key = { it.modelId }) { model ->
                ModelLibraryCard(
                    model = model,
                    onOpenDetail = { viewModel.openModelDetail(model.modelId) },
                    onDownload = {},
                    onCancel = {},
                    onDelete = {},
                    onOpenSettings = { viewModel.openOverlay(StudioOverlay.SETTINGS) }
                )
            }
        }
    }
}

@Composable
private fun ModelLibraryCard(
    model: ModelManifestEntity,
    geminiConnectionState: com.example.data.remote.GeminiConnectionState? = null,
    onOpenDetail: () -> Unit,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val provider = remember(model.modelId) { VerifiedModelCatalog.providerForModel(model.modelId) }
    val runtimeBadge = remember(model.runtimeCompatibility) {
        VerifiedModelCatalog.runtimeBadgeLabel(model.runtimeCompatibility)
    }
    val isDownloadable = remember(model) { VerifiedModelCatalog.isDownloadablePackage(model) }
    val isDirectGemini = model.runtimeCompatibility == VerifiedModelCatalog.RUNTIME_DIRECT_GEMINI_API
    val isUnsupported = model.runtimeCompatibility == VerifiedModelCatalog.RUNTIME_UNSUPPORTED_ON_ANDROID
    val isBusy = model.downloadState in setOf("DOWNLOADING", "DOWNLOADED", "VERIFYING", "LOADING", "PROCESSING")
    val isReady = model.downloadState in setOf("READY", "INSTALLED", "BUILT_IN")
    val stateColor = statusColorFor(model.downloadState)
    val remainingBytes = if (model.isSizeKnown && model.expectedSizeBytes > 0L) {
        (model.expectedSizeBytes - model.downloadedBytes).coerceAtLeast(0L)
    } else 0L

    StudioCard(
        padding = 14.dp,
        onClick = onOpenDetail,
        modifier = Modifier.testTag("model_card_${model.modelId}")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(model.modelName, style = MaterialTheme.typography.titleSmall, color = StudioTextPrimary)
                Text(
                    text = "$provider • $runtimeBadge",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                    color = StudioTextMuted
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            val pillText = when {
                isUnsupported -> "UNSUPPORTED"
                isDirectGemini && geminiConnectionState != null -> geminiConnectionState.pillLabel
                isDirectGemini && model.downloadState == VerifiedModelCatalog.STATE_KEY_SAVED_UNVERIFIED -> "KEY SAVED (UNVERIFIED)"
                isDirectGemini && isReady -> "VERIFIED CONNECTED"
                isDirectGemini -> model.downloadState.replace('_', ' ')
                model.downloadState == "BUILT_IN" -> "BUILT-IN"
                isReady && model.verificationLevel == "VERIFIED_SHA256" -> "READY (ONNX)"
                isReady -> "READY"
                else -> model.downloadState.replace('_', ' ')
            }
            val pillColor = when {
                isUnsupported -> StudioAmber
                isDirectGemini && geminiConnectionState != null -> statusColorFor(geminiConnectionState.name)
                else -> stateColor
            }
            StatusPill(
                text = pillText,
                color = pillColor
            )
        }

        Spacer(modifier = Modifier.height(6.dp))
        Text(model.purpose, style = MaterialTheme.typography.bodySmall, color = StudioTextSecondary)

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Format: ${model.fileFormat} • Languages: ${model.supportedLanguages}",
            style = MaterialTheme.typography.labelSmall,
            color = StudioTextMuted,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )

        if (isDownloadable) {
            Spacer(modifier = Modifier.height(8.dp))
            val expectedLabel = if (model.expectedSizeBytes > 0L) formatBytes(model.expectedSizeBytes) else "Unknown"
            val downloadedLabel = formatBytes(model.downloadedBytes)
            val remainingLabel = when {
                !model.isSizeKnown -> "—"
                isReady -> "0 B"
                else -> formatBytes(remainingBytes)
            }
            MetadataChipRow(
                items = listOf(
                    "Expected" to expectedLabel,
                    "Downloaded" to downloadedLabel,
                    "Remaining" to remainingLabel,
                    "Files" to "${model.verifiedFilesCount}/${model.totalFilesCount}"
                )
            )
        } else if (isUnsupported && model.isSizeKnown && model.expectedSizeBytes > 0L) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Upstream Desktop Size: ${formatBytes(model.expectedSizeBytes)} • ${model.statusNote}",
                style = MaterialTheme.typography.labelSmall,
                color = StudioAmber,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (isBusy || (isDownloadable && model.downloadedBytes > 0L && !isReady && model.isSizeKnown)) {
            Spacer(modifier = Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { (model.downloadProgressPercent / 100f).coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = if (isBusy) StudioCyan else StudioAmber,
                trackColor = StudioBorder
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = model.statusNote,
                style = MaterialTheme.typography.labelSmall,
                color = if (isBusy) StudioCyan else StudioTextSecondary
            )
        } else if (model.downloadState == "FAILED" || model.downloadState == "CORRUPTED") {
            Spacer(modifier = Modifier.height(8.dp))
            WarningBanner(message = model.statusNote, isError = true)
        }

        Spacer(modifier = Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = model.licenseName,
                style = MaterialTheme.typography.labelSmall,
                color = StudioTextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            when {
                isDirectGemini -> {
                    TextButton(
                        onClick = onOpenSettings,
                        modifier = Modifier.testTag("model_configure_gemini_btn")
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = null, tint = StudioViolet, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(if (isReady) "API Settings" else "Configure Key", color = StudioViolet)
                    }
                }
                isDownloadable -> {
                    when {
                        isBusy -> {
                            TextButton(
                                onClick = onCancel,
                                modifier = Modifier.testTag("model_cancel_${model.modelId}")
                            ) {
                                Icon(Icons.Default.Cancel, contentDescription = null, tint = StudioAmber, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Pause / Cancel", color = StudioAmber)
                            }
                        }
                        isReady -> {
                            IconButton(
                                onClick = onDelete,
                                modifier = Modifier.size(48.dp).testTag("model_delete_${model.modelId}")
                            ) {
                                Icon(Icons.Default.DeleteOutline, contentDescription = "Delete model files", tint = StudioCrimson)
                            }
                        }
                        else -> {
                            TextButton(
                                onClick = onDownload,
                                modifier = Modifier.testTag("model_download_${model.modelId}")
                            ) {
                                Icon(Icons.Default.Download, contentDescription = null, tint = StudioViolet, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = if (model.downloadedBytes > 0L) "Resume" else "Download",
                                    color = StudioViolet
                                )
                            }
                        }
                    }
                }
                else -> {
                    Text(
                        text = if (isUnsupported) "Tap to inspect why" else "Built-in",
                        style = MaterialTheme.typography.labelSmall,
                        color = StudioTextMuted
                    )
                }
            }
        }
    }
}

/**
 * MODEL ASSET & RUNTIME INSPECTOR OVERLAY
 * Displays official source URL, expected/downloaded/remaining bytes, honest Android compatibility,
 * per-file companion manifest breakdown with SHA-256 + ONNX load verification, and controls.
 */
@Composable
fun ModelDetailScreen(viewModel: StudioViewModel) {
    val models by viewModel.models.collectAsState()
    val selectedId by viewModel.selectedModelId.collectAsState()
    val geminiConnectionState by viewModel.geminiConnectionState.collectAsState()
    val model = models.find { it.modelId == selectedId }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = { viewModel.closeOverlay() },
                modifier = Modifier.size(48.dp).testTag("model_detail_back_btn")
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = StudioTextPrimary)
            }
            Column {
                Text("Model Asset Inspector", style = MaterialTheme.typography.titleLarge, color = StudioTextPrimary)
                if (model != null) {
                    Text(model.modelVersion.ifBlank { model.modelId }, style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily), color = StudioTextMuted)
                }
            }
        }

        if (model == null) {
            StudioEmptyState(
                icon = Icons.Default.Memory,
                title = "Model Not Found",
                description = "Return to the Model Library to select a verified model."
            )
            return
        }

        val isDownloadable = remember(model) { VerifiedModelCatalog.isDownloadablePackage(model) }
        val isDirectGemini = model.runtimeCompatibility == VerifiedModelCatalog.RUNTIME_DIRECT_GEMINI_API
        val isUnsupported = model.runtimeCompatibility == VerifiedModelCatalog.RUNTIME_UNSUPPORTED_ON_ANDROID
        val isBusy = model.downloadState in setOf("DOWNLOADING", "DOWNLOADED", "VERIFYING", "LOADING", "PROCESSING")
        val isReady = model.downloadState in setOf("READY", "INSTALLED")
        val diskStatuses = remember(model.modelId, model.filesManifestJson, model.downloadedBytes, model.downloadState) {
            viewModel.modelDownloadManager.inspectLocalFiles(model)
        }
        val remainingBytes = if (model.isSizeKnown && model.expectedSizeBytes > 0L) {
            (model.expectedSizeBytes - model.downloadedBytes).coerceAtLeast(0L)
        } else 0L

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 10.dp)
        ) {
            item {
                StudioCard(elevated = true) {
                    Text(model.modelName, style = MaterialTheme.typography.titleMedium, color = StudioTextPrimary)
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatusPill(text = VerifiedModelCatalog.runtimeBadgeLabel(model.runtimeCompatibility), color = if (isUnsupported) StudioAmber else StudioCyan)
                        StatusPill(
                            text = if (isDirectGemini) geminiConnectionState.pillLabel else model.downloadState.replace('_', ' '),
                            color = if (isDirectGemini) statusColorFor(geminiConnectionState.name) else statusColorFor(model.downloadState)
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(model.purpose, style = MaterialTheme.typography.bodyMedium, color = StudioTextSecondary)

                    if (isDownloadable) {
                        Spacer(modifier = Modifier.height(12.dp))
                        MetadataChipRow(
                            items = listOf(
                                "Expected Total" to if (model.isSizeKnown) formatBytes(model.expectedSizeBytes) else "Unknown",
                                "Downloaded" to formatBytes(model.downloadedBytes),
                                "Remaining" to if (model.isSizeKnown) formatBytes(remainingBytes) else "—",
                                "Verified Files" to "${model.verifiedFilesCount}/${model.totalFilesCount}"
                            )
                        )
                    }

                    if (isBusy) {
                        Spacer(modifier = Modifier.height(10.dp))
                        LinearProgressIndicator(
                            progress = { (model.downloadProgressPercent / 100f).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                            color = StudioCyan,
                            trackColor = StudioBorder
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = StudioBorderSubtle)
                    Spacer(modifier = Modifier.height(10.dp))

                    DetailRow("File Format", model.fileFormat)
                    DetailRow("Supported Languages", model.supportedLanguages)
                    DetailRow("Official Source", model.sourceUrl, mono = true)
                    DetailRow("License", "${model.licenseName} — ${model.licenseNotes}")
                    DetailRow("Runtime Compatibility", model.compatibilitySummary.ifBlank { model.runtimeCompatibility })
                    DetailRow("Required Dependencies", model.requiredDependencies)
                    if (isUnsupported && model.isSizeKnown && model.expectedSizeBytes > 0L) {
                        DetailRow("Upstream Desktop Package Size", "${formatBytes(model.expectedSizeBytes)} (${model.expectedSizeBytes} bytes)", mono = true)
                    }
                    if (model.localFilePath.isNotBlank()) {
                        DetailRow("Device Storage Directory", model.localFilePath, mono = true)
                    }
                    if (isDownloadable) {
                        val verLabel = when (model.verificationLevel) {
                            "VERIFIED_SHA256" -> "SHA-256 cryptographic digest + ONNX Runtime OrtSession load verified"
                            "VERIFIED_SIZE_ONLY" -> "Exact byte size verified"
                            else -> "Unverified / Not downloaded"
                        }
                        DetailRow(
                            "Integrity & Runtime Verification",
                            verLabel,
                            valueColor = when (model.verificationLevel) {
                                "VERIFIED_SHA256" -> StudioEmerald
                                "VERIFIED_SIZE_ONLY" -> StudioCyan
                                else -> StudioTextMuted
                            }
                        )
                    }
                    if (model.sha256Checksum.isNotBlank()) {
                        DetailRow("Verified Primary SHA-256", model.sha256Checksum, mono = true, valueColor = StudioEmerald)
                    }
                    if (model.statusNote.isNotBlank()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        WarningBanner(
                            message = model.statusNote,
                            isError = model.downloadState == "FAILED" || model.downloadState == "CORRUPTED"
                        )
                    }
                }
            }

            if (diskStatuses.isNotEmpty()) {
                item {
                    StudioCard {
                        Text(
                            text = "Required Files & Companion Assets (${diskStatuses.size})",
                            style = MaterialTheme.typography.titleSmall,
                            color = StudioTextPrimary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        diskStatuses.forEachIndexed { idx, status ->
                            if (idx > 0) {
                                HorizontalDivider(color = StudioBorderSubtle, modifier = Modifier.padding(vertical = 8.dp))
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = status.artifact.relativePath,
                                        style = MaterialTheme.typography.labelLarge.copy(fontFamily = JetBrainsMonoFamily),
                                        color = StudioTextPrimary
                                    )
                                    val sizeDesc = if (status.artifact.expectedBytes > 0L) {
                                        "${formatBytes(status.artifact.expectedBytes)} (${status.artifact.expectedBytes} B)"
                                    } else "Unknown"
                                    Text(
                                        text = "${status.artifact.role} • Expected: $sizeDesc",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = StudioTextSecondary
                                    )
                                    if (status.actualBytesOnDisk > 0L || status.partialBytesOnDisk > 0L) {
                                        Text(
                                            text = "On disk: ${formatBytes(status.actualBytesOnDisk + status.partialBytesOnDisk)}",
                                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                                            color = if (status.isPresentAndSized) StudioEmerald else StudioAmber
                                        )
                                    }
                                    if (status.artifact.expectedSha256.isNotBlank()) {
                                        Text(
                                            text = "SHA-256: ${status.artifact.expectedSha256.take(24)}…",
                                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                                            color = StudioTextMuted
                                        )
                                    }
                                    Text(
                                        text = status.artifact.downloadUrl,
                                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                                        color = StudioTextMuted,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                if (status.isPresentAndSized) {
                                    Icon(
                                        Icons.Default.CheckCircle,
                                        contentDescription = "File present and sized",
                                        tint = StudioEmerald,
                                        modifier = Modifier.size(20.dp)
                                    )
                                } else {
                                    StatusPill(
                                        text = if (status.partialBytesOnDisk > 0L) "PARTIAL" else "PENDING",
                                        color = if (status.partialBytesOnDisk > 0L) StudioAmber else StudioTextMuted
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (isDirectGemini) {
                item {
                    Button(
                        onClick = { viewModel.openOverlay(StudioOverlay.SETTINGS) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("detail_open_gemini_settings_btn"),
                        colors = ButtonDefaults.buttonColors(containerColor = StudioViolet, contentColor = Color(0xFF0A0B0F)),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.Key, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Configure Direct Gemini API Key & Model")
                    }
                }
            } else if (isDownloadable) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        if (isBusy) {
                            OutlinedButton(
                                onClick = { viewModel.cancelModelDownload(model.modelId) },
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("detail_cancel_download_btn"),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("Pause / Cancel Download", color = StudioAmber)
                            }
                        } else {
                            Button(
                                onClick = {
                                    if (isReady) {
                                        viewModel.verifyModelIntegrity(model.modelId)
                                    } else {
                                        viewModel.downloadModel(model.modelId)
                                    }
                                },
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("detail_download_btn"),
                                colors = ButtonDefaults.buttonColors(containerColor = StudioViolet, contentColor = Color(0xFF0A0B0F)),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(
                                    imageVector = if (isReady) Icons.Default.VerifiedUser else Icons.Default.Download,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    when {
                                        isReady -> "Re-Verify SHA-256 & ONNX Load"
                                        model.downloadedBytes > 0L -> "Resume Download"
                                        else -> "Download & Verify ONNX Model"
                                    }
                                )
                            }
                        }

                        if (isReady || model.downloadedBytes > 0L) {
                            OutlinedButton(
                                onClick = { viewModel.deleteModel(model.modelId) },
                                modifier = Modifier.heightIn(min = 48.dp).testTag("detail_delete_btn"),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("Delete", color = StudioCrimson)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailRow(
    label: String,
    value: String,
    mono: Boolean = false,
    valueColor: Color = StudioTextPrimary
) {
    if (value.isBlank()) return
    Text(label, style = MaterialTheme.typography.labelSmall, color = StudioTextMuted)
    Text(
        text = value,
        style = if (mono) {
            MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFamily)
        } else {
            MaterialTheme.typography.bodySmall
        },
        color = valueColor
    )
    Spacer(modifier = Modifier.height(8.dp))
}

/**
 * PROCESSING HISTORY OVERLAY
 */
@Composable
fun ProcessingHistoryScreen(viewModel: StudioViewModel) {
    val jobs by viewModel.allAgentJobHistory.collectAsState()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { viewModel.closeOverlay() }, modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = StudioTextPrimary)
            }
            Column {
                Text("Processing History", style = MaterialTheme.typography.titleLarge, color = StudioTextPrimary)
                Text("${jobs.size} recorded stage execution(s)", style = MaterialTheme.typography.bodySmall, color = StudioTextSecondary)
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        if (jobs.isEmpty()) {
            StudioEmptyState(
                icon = Icons.Default.History,
                title = "No Processing History Yet",
                description = "Stage executions, durations, outputs, and errors will appear here as you process episodes."
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(jobs, key = { it.id }) { job ->
                    StudioCard(padding = 12.dp) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Stage ${job.stageNumber} • ${job.agentName}",
                                style = MaterialTheme.typography.labelLarge,
                                color = StudioTextPrimary,
                                modifier = Modifier.weight(1f)
                            )
                            StatusPill(text = job.status, color = statusColorFor(job.status))
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(job.currentOperation, style = MaterialTheme.typography.bodySmall, color = StudioTextSecondary)
                        Text(
                            text = "${job.outputSummary} • ${formatElapsedMs(job.startedAt, job.updatedAt)} • ${formatShortDate(job.updatedAt)}",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                            color = StudioTextMuted
                        )
                        if (!job.errorDetails.isNullOrBlank()) {
                            Spacer(modifier = Modifier.height(6.dp))
                            WarningBanner(message = job.errorDetails, isError = true)
                        }
                    }
                }
            }
        }
    }
}

/**
 * DIRECT GEMINI API & ANDROID STUDIO SETTINGS OVERLAY
 * Manages encrypted Direct Gemini API key storage, model selection, live connection testing,
 * audio sample rate, and transparent Android-only local runtime diagnostics.
 */
@Composable
fun SettingsScreen(viewModel: StudioViewModel) {
    val config by viewModel.studioConfig.collectAsState()
    val geminiKeyState by viewModel.geminiKeyState.collectAsState()
    val geminiConnection by viewModel.geminiConnectionStatus.collectAsState()
    val geminiConnectionState by viewModel.geminiConnectionState.collectAsState()
    val isTestingGemini by viewModel.isTestingGeminiConnection.collectAsState()
    val scope = rememberCoroutineScope()

    var apiKeyInput by remember { mutableStateOf("") }
    val freeBytes = remember { viewModel.modelDownloadManager.getAvailableStorageBytes() }
    val modelBytes = remember { viewModel.modelDownloadManager.getModelsStorageUsedBytes() }
    val modelValidation = remember(config.geminiModelName) {
        DirectGeminiApiClient.validateModelSelection(config.geminiModelName)
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = { viewModel.closeOverlay() },
                modifier = Modifier.size(48.dp).testTag("settings_back_btn")
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = StudioTextPrimary)
            }
            Text("Studio & Gemini API Settings", style = MaterialTheme.typography.titleLarge, color = StudioTextPrimary)
        }

        Spacer(modifier = Modifier.height(12.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Direct Gemini API Key & Connection Test Card
            item {
                StudioCard(elevated = true) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.Key, contentDescription = null, tint = StudioViolet, modifier = Modifier.size(20.dp))
                            Text("Direct Gemini API", style = MaterialTheme.typography.titleMedium, color = StudioTextPrimary)
                        }
                        StatusPill(
                            text = geminiConnectionState.pillLabel,
                            color = statusColorFor(geminiConnectionState.name)
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "The ONLY online AI service used by AnimeDub Studio. Connects directly to Google's official Gemini REST API (generativelanguage.googleapis.com) with no Firebase or external worker proxy. Changing the API key or model resets verification until re-tested.",
                        style = MaterialTheme.typography.bodySmall,
                        color = StudioTextSecondary
                    )

                    Spacer(modifier = Modifier.height(10.dp))
                    val keySourceLabel = when (geminiKeyState.source) {
                        GeminiKeySource.USER_ENCRYPTED_KEYSTORE ->
                            "Active Key: ${geminiKeyState.maskedKey} (Android Keystore AES-256-GCM)"
                        GeminiKeySource.BUILD_CONFIG_SECRET ->
                            "Active Key: ${geminiKeyState.maskedKey} (AI Studio BuildConfig Secret)"
                        GeminiKeySource.NOT_CONFIGURED ->
                            "No Gemini API key configured. Enter your key below to enable translation, transcription, script repair, and quality review."
                    }
                    Text(
                        text = keySourceLabel,
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                        color = if (geminiKeyState.isConfigured) StudioEmerald else StudioAmber
                    )

                    if (!geminiKeyState.keystoreError.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        WarningBanner(
                            message = "Keystore error: ${geminiKeyState.keystoreError}",
                            isError = true
                        )
                    }

                    if (modelValidation.isFailure) {
                        Spacer(modifier = Modifier.height(8.dp))
                        WarningBanner(
                            message = modelValidation.exceptionOrNull()?.message
                                ?: "Selected model '${config.geminiModelName}' is retired or unsupported. Choose an active model below.",
                            isError = true
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = apiKeyInput,
                        onValueChange = { apiKeyInput = it },
                        label = { Text(if (geminiKeyState.hasUserStoredKey) "Update Gemini API Key" else "Enter Gemini API Key") },
                        placeholder = { Text("AIza...") },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth().testTag("settings_gemini_api_key_input"),
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = {
                                val trimmed = apiKeyInput.trim()
                                if (trimmed.isNotBlank()) {
                                    viewModel.saveGeminiApiKey(trimmed)
                                    apiKeyInput = ""
                                }
                            },
                            enabled = apiKeyInput.trim().isNotBlank(),
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("settings_save_gemini_key_btn"),
                            colors = ButtonDefaults.buttonColors(containerColor = StudioViolet, contentColor = Color(0xFF0A0B0F)),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("Save Key")
                        }
                        if (geminiKeyState.hasUserStoredKey) {
                            OutlinedButton(
                                onClick = {
                                    apiKeyInput = ""
                                    viewModel.removeGeminiApiKey()
                                },
                                modifier = Modifier.heightIn(min = 48.dp).testTag("settings_remove_gemini_key_btn"),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("Remove", color = StudioCrimson)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Gemini Model", style = MaterialTheme.typography.labelLarge, color = StudioTextPrimary)
                    Spacer(modifier = Modifier.height(6.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        DirectGeminiApiClient.SUPPORTED_MODELS.chunked(2).forEach { rowModels ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                rowModels.forEach { option ->
                                    FilterChip(
                                        selected = config.geminiModelName == option.modelId,
                                        onClick = { viewModel.updateSelectedGeminiModel(option.modelId) },
                                        label = {
                                            Text(
                                                option.modelId,
                                                style = MaterialTheme.typography.labelSmall,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        },
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("settings_gemini_${option.modelId}")
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = { viewModel.testGeminiConnection() },
                        enabled = !isTestingGemini && geminiKeyState.isConfigured,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("settings_test_gemini_btn"),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        if (isTestingGemini) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = StudioCyan,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Testing Direct Gemini Connection…", color = StudioCyan)
                        } else {
                            Icon(Icons.Default.VerifiedUser, contentDescription = null, tint = StudioCyan, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Test Gemini Connection", color = StudioCyan)
                        }
                    }

                    if (geminiConnection != null) {
                        val status = geminiConnection!!
                        Spacer(modifier = Modifier.height(8.dp))
                        WarningBanner(
                            message = if (status.isConnected) {
                                "Verified: ${status.message} (${status.latencyMs} ms)"
                            } else {
                                "Connection failed (${status.errorKind ?: "ERROR"}): ${status.message}"
                            },
                            isError = !status.isConnected
                        )
                    }
                }
            }

            // Security Notice for Client-Side Android API Keys
            item {
                StudioCard {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Security, contentDescription = null, tint = StudioAmber, modifier = Modifier.size(18.dp))
                        Text("API Key Storage & Security Notice", style = MaterialTheme.typography.titleSmall, color = StudioTextPrimary)
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Your Gemini API key is encrypted at rest using Android Keystore (AES-256-GCM), sent only over HTTPS via the x-goog-api-key header, and never logged or written into project exports. Client-side Android apps cannot guarantee absolute secret extraction resistance on rooted/compromised devices; keep API quotas restricted and use a dedicated backend proxy for multi-user commercial releases.",
                        style = MaterialTheme.typography.bodySmall,
                        color = StudioTextSecondary
                    )
                }
            }

            // Android-Only Local Architecture & Audio Settings
            item {
                StudioCard {
                    Text("Default Audio Sample Rate", style = MaterialTheme.typography.titleSmall, color = StudioTextPrimary)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(24000, 44100, 48000).forEach { sr ->
                            FilterChip(
                                selected = config.defaultAudioSampleRateHz == sr,
                                onClick = {
                                    scope.launch {
                                        viewModel.prefsRepo.updateDefaultExportSettings(
                                            resolution = config.defaultExportResolution,
                                            videoBitrateKbps = config.defaultVideoBitrateKbps,
                                            audioCodec = config.defaultAudioCodec,
                                            audioBitrateKbps = config.defaultAudioBitrateKbps,
                                            audioSampleRateHz = sr
                                        )
                                    }
                                },
                                label = { Text("${sr / 1000} kHz") }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("On-Device Android Processing Stack", style = MaterialTheme.typography.titleSmall, color = StudioTextPrimary)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "• Voice Activity Detection: Silero VAD v5 (ONNX Runtime)\n• Voice Synthesis: Android System TextToSpeech (Explicit local fallback — no neural voice cloning)\n• Stem Separation: On-Device Bandpass/Residual DSP (non-neural)\n• Speaker Grouping: On-Device Acoustic F0/Spectral Clustering (audio-only)\n• Mixing & Export: MediaCodec / MediaMuxer + WSOLA Time-Stretch",
                        style = MaterialTheme.typography.bodySmall,
                        color = StudioTextSecondary
                    )
                }
            }

            item {
                StudioCard {
                    Text("Device Storage", style = MaterialTheme.typography.titleSmall, color = StudioTextPrimary)
                    Spacer(modifier = Modifier.height(8.dp))
                    MetadataChipRow(
                        items = listOf(
                            "Available" to formatBytes(freeBytes),
                            "Model Cache" to formatBytes(modelBytes)
                        )
                    )
                }
            }
        }
    }
}
