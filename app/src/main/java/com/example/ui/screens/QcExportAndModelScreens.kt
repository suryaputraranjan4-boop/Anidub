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
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FactCheck
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Outbox
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.local.ModelManifestEntity
import com.example.engine.HinglishTtsNormalizer
import com.example.engine.ModelDownloadManager
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
                    Text(if (exportFormat == "MP4_VIDEO") "Export Muxed MP4 Video" else "Export Lossless WAV Audio")
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
                            Text(
                                text = formatShortDate(exp.exportedAt),
                                style = MaterialTheme.typography.labelSmall,
                                color = StudioTextMuted
                            )
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
 * Cleanly separates Local / On-Device Models from Remote API & GPU Worker Services.
 * Shows real progress bars ONLY during active download/verification.
 */
@Composable
fun ModelLibraryScreen(viewModel: StudioViewModel) {
    val models by viewModel.models.collectAsState()
    val localModels = remember(models) { models.filter { ModelDownloadManager.isLocalRuntime(it.runtimeCompatibility) } }
    val remoteModels = remember(models) { models.filterNot { ModelDownloadManager.isLocalRuntime(it.runtimeCompatibility) } }
    val usedBytes = remember(models) { viewModel.modelDownloadManager.getModelsStorageUsedBytes() }
    val freeBytes = remember(models) { viewModel.modelDownloadManager.getAvailableStorageBytes() }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("model_library_list"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            StudioSectionHeader(
                title = "Model Library",
                subtitle = "Verified on-device DSP/ONNX runtimes & remote API / GPU worker services"
            )
            Spacer(modifier = Modifier.height(6.dp))
            MetadataChipRow(
                items = listOf(
                    "On-Device" to "${localModels.size} runtimes",
                    "Remote / API" to "${remoteModels.size} services",
                    "Disk Free" to formatBytes(freeBytes)
                )
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Downloaded manifest/model cache on device: ${formatBytes(usedBytes)}",
                style = MaterialTheme.typography.labelSmall,
                color = StudioTextMuted
            )
        }

        // Section 1: Local / On-Device Models
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 6.dp)
            ) {
                Icon(Icons.Default.PhoneAndroid, contentDescription = null, tint = StudioEmerald, modifier = Modifier.size(18.dp))
                Text("Local / On-Device Models", style = MaterialTheme.typography.titleMedium, color = StudioTextPrimary)
            }
        }
        items(localModels, key = { it.modelId }) { model ->
            ModelLibraryCard(
                model = model,
                onOpenDetail = { viewModel.openModelDetail(model.modelId) },
                onDownload = { viewModel.downloadModel(model.modelId) },
                onDelete = { viewModel.deleteModel(model.modelId) }
            )
        }

        // Section 2: Remote API & GPU Worker Services
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 8.dp)
            ) {
                Icon(Icons.Default.Cloud, contentDescription = null, tint = StudioCyan, modifier = Modifier.size(18.dp))
                Text("Remote API & GPU Worker Services", style = MaterialTheme.typography.titleMedium, color = StudioTextPrimary)
            }
        }
        items(remoteModels, key = { it.modelId }) { model ->
            ModelLibraryCard(
                model = model,
                onOpenDetail = { viewModel.openModelDetail(model.modelId) },
                onDownload = { viewModel.downloadModel(model.modelId) },
                onDelete = { viewModel.deleteModel(model.modelId) }
            )
        }
    }
}

@Composable
private fun ModelLibraryCard(
    model: ModelManifestEntity,
    onOpenDetail: () -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit
) {
    val provider = remember(model.modelId) { ModelDownloadManager.providerForModel(model.modelId) }
    val isDownloading = model.downloadState == "DOWNLOADING" || model.downloadState == "VERIFYING"
    val isBuiltInOrApi = model.runtimeCompatibility in setOf("ANDROID_LOCAL", "NATIVE_CPP_DSP", "EXTERNAL_API")
    val stateColor = statusColorFor(model.downloadState)

    StudioCard(padding = 14.dp, onClick = onOpenDetail) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(model.modelName, style = MaterialTheme.typography.titleSmall, color = StudioTextPrimary)
                Text(
                    text = "$provider • ${model.runtimeCompatibility.replace('_', ' ')}",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                    color = StudioTextMuted
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            StatusPill(
                text = when {
                    model.runtimeCompatibility == "EXTERNAL_API" -> "FIREBASE AI"
                    model.downloadState == "INSTALLED" -> "READY"
                    else -> model.downloadState.replace('_', ' ')
                },
                color = stateColor
            )
        }

        Spacer(modifier = Modifier.height(6.dp))
        Text(model.purpose, style = MaterialTheme.typography.bodySmall, color = StudioTextSecondary)

        if (model.isExperimentalArchived) {
            Spacer(modifier = Modifier.height(6.dp))
            StatusPill(text = "ARCHIVED RESEARCH (AUG 2026)", color = StudioAmber)
        }

        // Show progress bar ONLY during active download or SHA-256 verification
        if (isDownloading) {
            Spacer(modifier = Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { (model.downloadProgressPercent / 100f).coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = StudioCyan,
                trackColor = StudioBorder
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(model.statusNote, style = MaterialTheme.typography.labelSmall, color = StudioCyan)
        }

        Spacer(modifier = Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${model.licenseName}${if (model.expectedSizeBytes > 0L) " • ${formatBytes(model.expectedSizeBytes)}" else ""}",
                style = MaterialTheme.typography.labelSmall,
                color = StudioTextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (!isBuiltInOrApi) {
                if (model.downloadState == "INSTALLED") {
                    IconButton(onClick = onDelete, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Default.DeleteOutline, contentDescription = "Delete model", tint = StudioCrimson)
                    }
                } else if (!isDownloading) {
                    TextButton(
                        onClick = if (model.isGatedHuggingFace) onOpenDetail else onDownload
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null, tint = StudioViolet, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(if (model.isGatedHuggingFace) "Token Required" else "Install", color = StudioViolet)
                    }
                }
            }
        }
    }
}

/**
 * MODEL MANIFEST & CHECKSUM DETAIL OVERLAY
 */
@Composable
fun ModelDetailScreen(viewModel: StudioViewModel) {
    val models by viewModel.models.collectAsState()
    val selectedId by viewModel.selectedModelId.collectAsState()
    val model = models.find { it.modelId == selectedId }
    var hfToken by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { viewModel.closeOverlay() }, modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = StudioTextPrimary)
            }
            Text("Model Details", style = MaterialTheme.typography.titleLarge, color = StudioTextPrimary)
        }

        if (model == null) {
            StudioEmptyState(
                icon = Icons.Default.Memory,
                title = "Model Not Found",
                description = "Return to the Model Library to select a verified model."
            )
            return
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 10.dp)
        ) {
            item {
                StudioCard {
                    Text(model.modelName, style = MaterialTheme.typography.titleMedium, color = StudioTextPrimary)
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatusPill(text = model.runtimeCompatibility.replace('_', ' '), color = StudioCyan)
                        StatusPill(text = model.downloadState.replace('_', ' '), color = statusColorFor(model.downloadState))
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(model.purpose, style = MaterialTheme.typography.bodyMedium, color = StudioTextSecondary)
                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = StudioBorderSubtle)
                    Spacer(modifier = Modifier.height(10.dp))
                    Text("Required Files", style = MaterialTheme.typography.labelSmall, color = StudioTextMuted)
                    Text(model.actualFilesNeededJson, style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFamily), color = StudioTextPrimary)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Dependencies", style = MaterialTheme.typography.labelSmall, color = StudioTextMuted)
                    Text(model.requiredDependencies, style = MaterialTheme.typography.bodySmall, color = StudioTextSecondary)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("License", style = MaterialTheme.typography.labelSmall, color = StudioTextMuted)
                    Text("${model.licenseName} — ${model.licenseNotes}", style = MaterialTheme.typography.bodySmall, color = StudioTextSecondary)
                    if (model.sha256Checksum.isNotBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Verified SHA-256", style = MaterialTheme.typography.labelSmall, color = StudioEmerald)
                        Text(model.sha256Checksum, style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily), color = StudioTextPrimary)
                    }
                    if (model.statusNote.isNotBlank()) {
                        Spacer(modifier = Modifier.height(10.dp))
                        WarningBanner(message = model.statusNote, isError = model.downloadState == "FAILED")
                    }
                }
            }

            if (model.isGatedHuggingFace) {
                item {
                    StudioCard {
                        Text("Hugging Face Access Token", style = MaterialTheme.typography.titleSmall, color = StudioTextPrimary)
                        Spacer(modifier = Modifier.height(6.dp))
                        OutlinedTextField(
                            value = hfToken,
                            onValueChange = { hfToken = it },
                            label = { Text("Read-Only HF Token (hf_...)") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    }
                }
            }

            if (model.runtimeCompatibility !in setOf("ANDROID_LOCAL", "NATIVE_CPP_DSP", "EXTERNAL_API")) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = { viewModel.downloadModel(model.modelId, hfToken.takeIf { it.isNotBlank() }) },
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = StudioViolet, contentColor = Color(0xFF0A0B0F)),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text(if (model.downloadState == "INSTALLED") "Re-Verify Manifest" else "Download & Verify")
                        }
                        if (model.downloadState == "INSTALLED") {
                            OutlinedButton(
                                onClick = { viewModel.deleteModel(model.modelId) },
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
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
                        "Optional endpoint for heavy Python/PyTorch models (Faster-Whisper Large-v3, pyannote, XTTS-v2).",
                        style = MaterialTheme.typography.bodySmall,
                        color = StudioTextSecondary
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = workerUrl,
                        onValueChange = { workerUrl = it },
                        label = { Text("Worker Base URL") },
                        placeholder = { Text("http://192.168.1.100:8080") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = {
                                val cleanUrl = workerUrl.trim()
                                scope.launch {
                                    viewModel.prefsRepo.updateWorkerSettings(
                                        enabled = cleanUrl.isNotBlank(),
                                        url = cleanUrl,
                                        hasAuthToken = config.remoteWorkerHasAuthToken,
                                        gpuAvailable = config.workerGpuAvailable,
                                        maxConcurrency = config.maxProcessingConcurrency,
                                        timeoutSeconds = config.requestTimeoutSeconds,
                                        uploadLimitMb = config.maxUploadLimitMb
                                    )
                                }
                                viewModel.verifyRemoteWorkerHealth(cleanUrl)
                            },
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
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
