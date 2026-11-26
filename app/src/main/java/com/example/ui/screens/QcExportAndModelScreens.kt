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
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FactCheck
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Outbox
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
    var fileName by remember(ep.id) { mutableStateOf("AnimeDub_Ep${ep.episodeNumber}_Hinglish") }

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
                        label = { Text("Audio-Only WAV (Lossless)") },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = exportFormat == "MP4_VIDEO",
                        onClick = { exportFormat = "MP4_VIDEO" },
                        label = { Text("Final Video MP4") },
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = if (exportFormat == "MP4_VIDEO") {
                        "Muxes the original source video track with AAC-LC encoded dubbed audio into an MP4 container. Requires an imported file with a video stream."
                    } else {
                        "Exports a lossless 16-bit PCM WAV master containing synchronized dubbed dialogue and preserved background accompaniment."
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
                            videoBitrateKbps = 8500,
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
                            Text(
                                text = "${exp.exportFormat.replace('_', ' ')} • ${exp.audioCodec} • ${formatBytes(exp.fileSizeBytes)} • ${formatDurationCompact(exp.durationMs)}",
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
 * Shows verified downloadable AI models (with exact Hugging Face combined sizes, companion counts,
 * formats, languages, and honest runtime compatibility) separated from built-in Android & Firebase API runtimes.
 */
@Composable
fun ModelLibraryScreen(viewModel: StudioViewModel) {
    val models by viewModel.models.collectAsState()
    val downloadableModels = remember(models) {
        models.filter { VerifiedModelCatalog.isDownloadablePackage(it) }
    }
    val builtInAndCloudModels = remember(models) {
        models.filterNot { VerifiedModelCatalog.isDownloadablePackage(it) }
    }
    val usedBytes = remember(models) { viewModel.modelDownloadManager.getModelsStorageUsedBytes() }
    val freeBytes = remember(models) { viewModel.modelDownloadManager.getAvailableStorageBytes() }
    val totalKnownPackageBytes = remember(downloadableModels) {
        downloadableModels.filter { it.isSizeKnown }.sumOf { it.expectedSizeBytes }
    }
    val installedCount = remember(downloadableModels) {
        downloadableModels.count { it.downloadState == "INSTALLED" }
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
                title = "Model Assets & Downloads",
                subtitle = "Verified official Hugging Face weights, companion manifests, and honest runtime compatibility"
            )
            Spacer(modifier = Modifier.height(6.dp))
            MetadataChipRow(
                items = listOf(
                    "Verified Packs" to "$installedCount / ${downloadableModels.size}",
                    "Catalog Total" to "${formatBytes(totalKnownPackageBytes)}+",
                    "Disk Free" to formatBytes(freeBytes)
                )
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "On-device model storage used: ${formatBytes(usedBytes)} • Ungated catalog requires ${formatBytes(totalKnownPackageBytes)} (+ gated pyannote after HF auth)",
                style = MaterialTheme.typography.labelSmall,
                color = StudioTextMuted
            )
        }

        // Section 1: Verified Downloadable AI Model Packages
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 6.dp)
            ) {
                Icon(Icons.Default.Download, contentDescription = null, tint = StudioViolet, modifier = Modifier.size(18.dp))
                Text("Downloadable AI Models (Verified Sources)", style = MaterialTheme.typography.titleMedium, color = StudioTextPrimary)
            }
        }
        items(downloadableModels, key = { it.modelId }) { model ->
            ModelLibraryCard(
                model = model,
                onOpenDetail = { viewModel.openModelDetail(model.modelId) },
                onDownload = { viewModel.downloadModel(model.modelId) },
                onCancel = { viewModel.cancelModelDownload(model.modelId) },
                onDelete = { viewModel.deleteModel(model.modelId) }
            )
        }

        // Section 2: Built-In Android & Firebase Cloud Services (0 B Download)
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 8.dp)
            ) {
                Icon(Icons.Default.PhoneAndroid, contentDescription = null, tint = StudioEmerald, modifier = Modifier.size(18.dp))
                Text("Built-In Android & Cloud Services (0 B)", style = MaterialTheme.typography.titleMedium, color = StudioTextPrimary)
            }
        }
        items(builtInAndCloudModels, key = { it.modelId }) { model ->
            ModelLibraryCard(
                model = model,
                onOpenDetail = { viewModel.openModelDetail(model.modelId) },
                onDownload = {},
                onCancel = {},
                onDelete = {}
            )
        }
    }
}

@Composable
private fun ModelLibraryCard(
    model: ModelManifestEntity,
    onOpenDetail: () -> Unit,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit
) {
    val provider = remember(model.modelId) { VerifiedModelCatalog.providerForModel(model.modelId) }
    val runtimeBadge = remember(model.runtimeCompatibility) {
        VerifiedModelCatalog.runtimeBadgeLabel(model.runtimeCompatibility)
    }
    val isDownloadable = remember(model) { VerifiedModelCatalog.isDownloadablePackage(model) }
    val isDownloading = model.downloadState == "DOWNLOADING" || model.downloadState == "VERIFYING"
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
                model.runtimeCompatibility == VerifiedModelCatalog.RUNTIME_EXTERNAL_API -> "FIREBASE AI"
                model.downloadState == "BUILT_IN" -> "BUILT-IN"
                model.downloadState == "INSTALLED" && model.verificationLevel == "VERIFIED_SHA256" -> "SHA-256 VERIFIED"
                model.downloadState == "INSTALLED" && model.verificationLevel == "VERIFIED_SIZE_ONLY" -> "SIZE VERIFIED"
                model.downloadState == "INSTALLED" -> "INSTALLED"
                else -> model.downloadState.replace('_', ' ')
            }
            val pillColor = when {
                model.downloadState == "INSTALLED" && model.verificationLevel == "VERIFIED_SIZE_ONLY" -> StudioCyan
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
        // Format & Languages metadata strip
        Text(
            text = "Format: ${model.fileFormat} • Languages: ${model.supportedLanguages}",
            style = MaterialTheme.typography.labelSmall,
            color = StudioTextMuted,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )

        if (isDownloadable) {
            Spacer(modifier = Modifier.height(8.dp))
            val expectedLabel = when {
                !model.isSizeKnown && model.isGatedHuggingFace -> "Gated (HF token required)"
                model.expectedSizeBytes > 0L -> formatBytes(model.expectedSizeBytes)
                else -> "Unknown"
            }
            val downloadedLabel = formatBytes(model.downloadedBytes)
            val remainingLabel = when {
                !model.isSizeKnown -> "—"
                model.downloadState == "INSTALLED" -> "0 B"
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
        }

        // Progress bar during active download/verification or partial resume state
        if (isDownloading || (isDownloadable && model.downloadedBytes > 0L && model.downloadState != "INSTALLED" && model.isSizeKnown)) {
            Spacer(modifier = Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { (model.downloadProgressPercent / 100f).coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = if (isDownloading) StudioCyan else StudioAmber,
                trackColor = StudioBorder
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = model.statusNote,
                style = MaterialTheme.typography.labelSmall,
                color = if (isDownloading) StudioCyan else StudioTextSecondary
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
            if (isDownloadable) {
                when {
                    isDownloading -> {
                        TextButton(
                            onClick = onCancel,
                            modifier = Modifier.testTag("model_cancel_${model.modelId}")
                        ) {
                            Icon(Icons.Default.Cancel, contentDescription = null, tint = StudioAmber, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Pause / Cancel", color = StudioAmber)
                        }
                    }
                    model.downloadState == "INSTALLED" -> {
                        IconButton(
                            onClick = onDelete,
                            modifier = Modifier.size(48.dp).testTag("model_delete_${model.modelId}")
                        ) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = "Delete model files", tint = StudioCrimson)
                        }
                    }
                    model.isGatedHuggingFace -> {
                        TextButton(
                            onClick = onOpenDetail,
                            modifier = Modifier.testTag("model_gated_${model.modelId}")
                        ) {
                            Icon(Icons.Default.Lock, contentDescription = null, tint = StudioAmber, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("HF Token Required", color = StudioAmber)
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
        }
    }
}

/**
 * MODEL ASSET & COMPANION FILES DETAIL OVERLAY
 * Displays full official source URL, expected/downloaded/remaining bytes, compatibility summary,
 * per-file companion manifest breakdown with individual sizes & SHA-256 verification, and controls.
 */
@Composable
fun ModelDetailScreen(viewModel: StudioViewModel) {
    val models by viewModel.models.collectAsState()
    val selectedId by viewModel.selectedModelId.collectAsState()
    val model = models.find { it.modelId == selectedId }
    var hfToken by remember(selectedId) { mutableStateOf("") }

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
        val isDownloading = model.downloadState == "DOWNLOADING" || model.downloadState == "VERIFYING"
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
            // Core Metadata Card
            item {
                StudioCard(elevated = true) {
                    Text(model.modelName, style = MaterialTheme.typography.titleMedium, color = StudioTextPrimary)
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatusPill(text = VerifiedModelCatalog.runtimeBadgeLabel(model.runtimeCompatibility), color = StudioCyan)
                        StatusPill(text = model.downloadState.replace('_', ' '), color = statusColorFor(model.downloadState))
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(model.purpose, style = MaterialTheme.typography.bodyMedium, color = StudioTextSecondary)

                    if (isDownloadable) {
                        Spacer(modifier = Modifier.height(12.dp))
                        MetadataChipRow(
                            items = listOf(
                                "Expected Total" to if (model.isSizeKnown) formatBytes(model.expectedSizeBytes) else "Token required",
                                "Downloaded" to formatBytes(model.downloadedBytes),
                                "Remaining" to if (model.isSizeKnown) formatBytes(remainingBytes) else "—",
                                "Verified Files" to "${model.verifiedFilesCount}/${model.totalFilesCount}"
                            )
                        )
                    }

                    if (isDownloading) {
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
                    if (model.localFilePath.isNotBlank()) {
                        DetailRow("Device Storage Directory", model.localFilePath, mono = true)
                    }
                    if (isDownloadable) {
                        val verLabel = when (model.verificationLevel) {
                            "VERIFIED_SHA256" -> "SHA-256 cryptographic digest verified"
                            "VERIFIED_SIZE_ONLY" -> "Exact byte size verified (no official upstream SHA-256 published)"
                            else -> "Unverified / Not installed"
                        }
                        DetailRow(
                            "Integrity Verification",
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

            // Required Companion Files Breakdown Card
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
                                    val sizeDesc = when {
                                        status.artifact.expectedBytes > 0L ->
                                            "${formatBytes(status.artifact.expectedBytes)} (${status.artifact.expectedBytes} B)"
                                        status.artifact.isGatedFile -> "Gated — query with HF token"
                                        else -> "Unknown"
                                    }
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
                                } else if (status.artifact.isGatedFile && status.artifact.expectedBytes <= 0L) {
                                    Icon(
                                        Icons.Default.Lock,
                                        contentDescription = "Gated file",
                                        tint = StudioAmber,
                                        modifier = Modifier.size(18.dp)
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

            // Gated Hugging Face Token Panel
            if (model.isGatedHuggingFace) {
                item {
                    StudioCard {
                        Text("Gated Hugging Face Authorization", style = MaterialTheme.typography.titleSmall, color = StudioTextPrimary)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Accept license terms on pyannote/speaker-diarization-3.1 AND pyannote/segmentation-3.0, then enter a read-only HF token to inspect real server sizes or download.",
                            style = MaterialTheme.typography.bodySmall,
                            color = StudioTextSecondary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = hfToken,
                            onValueChange = { hfToken = it },
                            label = { Text("Read-Only HF Token (hf_...)") },
                            modifier = Modifier.fillMaxWidth().testTag("hf_token_input"),
                            singleLine = true
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { viewModel.probeGatedModelSizes(model.modelId, hfToken) },
                            enabled = hfToken.isNotBlank() && !isDownloading,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("probe_gated_size_btn"),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("Query Exact Server File Sizes (HEAD)", color = StudioCyan)
                        }
                    }
                }
            }

            // Download / Cancel / Verify / Delete Controls
            if (isDownloadable) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        if (isDownloading) {
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
                                    if (model.downloadState == "INSTALLED") {
                                        viewModel.verifyModelIntegrity(model.modelId)
                                    } else {
                                        viewModel.downloadModel(model.modelId, hfToken.takeIf { it.isNotBlank() })
                                    }
                                },
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("detail_download_btn"),
                                colors = ButtonDefaults.buttonColors(containerColor = StudioViolet, contentColor = Color(0xFF0A0B0F)),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(
                                    imageVector = if (model.downloadState == "INSTALLED") Icons.Default.VerifiedUser else Icons.Default.Download,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    when {
                                        model.downloadState == "INSTALLED" && model.sha256Checksum.isNotBlank() -> "Re-Verify SHA-256"
                                        model.downloadState == "INSTALLED" -> "Re-Verify Files"
                                        model.downloadedBytes > 0L -> "Resume Download"
                                        else -> "Download All Files"
                                    }
                                )
                            }
                        }

                        if (model.downloadState == "INSTALLED" || model.downloadedBytes > 0L) {
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
 * STUDIO & REMOTE WORKER SETTINGS OVERLAY
 */
@Composable
fun SettingsScreen(viewModel: StudioViewModel) {
    val config by viewModel.studioConfig.collectAsState()
    val workerHealth by viewModel.workerHealth.collectAsState()
    val scope = rememberCoroutineScope()

    var workerUrl by remember(config.remoteWorkerBaseUrl) { mutableStateOf(config.remoteWorkerBaseUrl) }
    var workerToken by remember(config.remoteWorkerAuthToken) { mutableStateOf(config.remoteWorkerAuthToken) }
    var allowLocalHttp by remember(config.allowInsecureLocalHttp) { mutableStateOf(config.allowInsecureLocalHttp) }
    val freeBytes = remember { viewModel.modelDownloadManager.getAvailableStorageBytes() }
    val modelBytes = remember { viewModel.modelDownloadManager.getModelsStorageUsedBytes() }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { viewModel.closeOverlay() }, modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = StudioTextPrimary)
            }
            Text("Studio Settings", style = MaterialTheme.typography.titleLarge, color = StudioTextPrimary)
        }

        Spacer(modifier = Modifier.height(12.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                StudioCard {
                    Text("Self-Hosted GPU Inference Worker", style = MaterialTheme.typography.titleSmall, color = StudioTextPrimary)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Optional endpoint for Python/PyTorch Safetensors models (Whisper Large V3 Turbo, HTDemucs, pyannote 3.1, F5-Hindi-24KHz). Uses HTTPS by default.",
                        style = MaterialTheme.typography.bodySmall,
                        color = StudioTextSecondary
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = workerUrl,
                        onValueChange = { workerUrl = it },
                        label = { Text("Worker Base URL") },
                        placeholder = { Text("https://worker.yourdomain.com or http://192.168.1.100:8080") },
                        modifier = Modifier.fillMaxWidth().testTag("settings_worker_url_input"),
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = workerToken,
                        onValueChange = { workerToken = it },
                        label = { Text("Bearer Auth Token (Optional)") },
                        placeholder = { Text("Sent as Authorization: Bearer ...") },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth().testTag("settings_worker_token_input"),
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Allow HTTP on Private LAN",
                                style = MaterialTheme.typography.labelLarge,
                                color = StudioTextPrimary
                            )
                            Text(
                                "Permits cleartext HTTP only for localhost/10.x/172.16-31.x/192.168.x hosts.",
                                style = MaterialTheme.typography.labelSmall,
                                color = StudioTextMuted
                            )
                        }
                        Switch(
                            checked = allowLocalHttp,
                            onCheckedChange = { allowLocalHttp = it },
                            modifier = Modifier.testTag("settings_allow_lan_http_switch")
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = {
                                val cleanUrl = workerUrl.trim()
                                val cleanToken = workerToken.trim()
                                scope.launch {
                                    viewModel.prefsRepo.updateWorkerSettings(
                                        enabled = cleanUrl.isNotBlank(),
                                        url = cleanUrl,
                                        authToken = cleanToken,
                                        hasAuthToken = cleanToken.isNotBlank(),
                                        allowInsecureLocalHttp = allowLocalHttp,
                                        gpuAvailable = config.workerGpuAvailable,
                                        maxConcurrency = config.maxProcessingConcurrency,
                                        timeoutSeconds = config.requestTimeoutSeconds,
                                        uploadLimitMb = config.maxUploadLimitMb
                                    )
                                }
                                viewModel.verifyRemoteWorkerHealth(cleanUrl, cleanToken, allowLocalHttp)
                            },
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("settings_save_test_worker_btn"),
                            colors = ButtonDefaults.buttonColors(containerColor = StudioViolet, contentColor = Color(0xFF0A0B0F)),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("Save & Test Connection")
                        }
                    }
                    if (workerHealth != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        WarningBanner(
                            message = if (workerHealth!!.reachable) {
                                "Worker reachable (${workerHealth!!.deviceType}) — ${workerHealth!!.statusMessage}"
                            } else {
                                "Worker unreachable: ${workerHealth!!.statusMessage}"
                            },
                            isError = !workerHealth!!.reachable
                        )
                    }
                }
            }

            item {
                StudioCard {
                    Text("Firebase AI Logic (Gemini Model)", style = MaterialTheme.typography.titleSmall, color = StudioTextPrimary)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Used for Japanese ASR fallback (Stage 5) and Roman Hinglish translation (Stage 8) via Firebase App Check.",
                        style = MaterialTheme.typography.bodySmall,
                        color = StudioTextSecondary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("gemini-3.8-flash", "gemini-3.5-flash-lite", "gemini-4-argon").forEach { modelId ->
                            FilterChip(
                                selected = config.geminiModelName == modelId,
                                onClick = {
                                    scope.launch {
                                        viewModel.prefsRepo.updateGeminiModelName(modelId)
                                    }
                                },
                                label = { Text(modelId, style = MaterialTheme.typography.labelSmall) },
                                modifier = Modifier.testTag("settings_gemini_$modelId")
                            )
                        }
                    }
                }
            }

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
                    Text("Default Voice Engine Profile", style = MaterialTheme.typography.titleSmall, color = StudioTextPrimary)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        HinglishTtsNormalizer.AVAILABLE_TTS_ENGINES.forEach { eng ->
                            FilterChip(
                                selected = config.selectedTtsEngine == eng.engineId,
                                onClick = {
                                    scope.launch {
                                        viewModel.prefsRepo.updateModelSelections(
                                            asrVariant = config.selectedAsrVariant,
                                            ttsEngine = eng.engineId,
                                            separationModel = config.selectedSeparationModel
                                        )
                                    }
                                },
                                label = { Text(eng.engineId, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }
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
