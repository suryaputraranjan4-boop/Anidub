package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.data.local.EpisodeEntity
import com.example.data.local.ProjectEntity
import com.example.engine.AudioDspEngine
import com.example.engine.AudioPlaybackState
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
import com.example.ui.theme.speakerColorFor
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun speakerAccentColor(label: String): Color = speakerColorFor(label)

fun formatTimeMs(ms: Long): String {
    val safe = ms.coerceAtLeast(0L)
    val totalSec = safe / 1000L
    val mins = totalSec / 60L
    val secs = totalSec % 60L
    val millis = (safe % 1000L) / 10L
    return "%02d:%02d.%02d".format(mins, secs, millis)
}

fun formatDurationCompact(ms: Long): String {
    if (ms <= 0L) return "0:00"
    val totalSec = ms / 1000L
    val hrs = totalSec / 3600L
    val mins = (totalSec % 3600L) / 60L
    val secs = totalSec % 60L
    return if (hrs > 0L) "%d:%02d:%02d".format(hrs, mins, secs) else "%d:%02d".format(mins, secs)
}

fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    return when {
        bytes >= 1_000_000_000L -> "%.2f GB".format(Locale.US, bytes / 1_000_000_000.0)
        bytes >= 1_000_000L -> "%.2f MB".format(Locale.US, bytes / 1_000_000.0)
        bytes >= 1_000L -> "%.1f KB".format(Locale.US, bytes / 1_000.0)
        else -> "$bytes B"
    }
}

fun formatElapsedMs(startMs: Long, endMs: Long): String {
    val diff = (endMs - startMs).coerceAtLeast(0L)
    return when {
        diff < 1000L -> "${diff}ms"
        diff < 60_000L -> "%.1fs".format(diff / 1000.0)
        else -> "${diff / 60_000L}m ${(diff % 60_000L) / 1000L}s"
    }
}

fun formatShortDate(epochMs: Long): String {
    if (epochMs <= 0L) return "—"
    return SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(epochMs))
}

@Composable
fun StudioCard(
    modifier: Modifier = Modifier,
    elevated: Boolean = false,
    accentBorder: Color? = null,
    padding: Dp = 16.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val container = if (elevated) StudioSurfaceElevated else StudioSurface
    val borderColor = accentBorder ?: if (elevated) StudioBorder else StudioBorderSubtle
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        shape = RoundedCornerShape(14.dp),
        color = container,
        border = BorderStroke(1.dp, borderColor),
        tonalElevation = 0.dp
    ) {
        Column(
            modifier = Modifier.padding(padding),
            content = content
        )
    }
}

@Composable
fun StatusPill(
    text: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(6.dp),
        color = color.copy(alpha = 0.14f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.35f))
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.SemiBold,
                fontFamily = JetBrainsMonoFamily
            ),
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

fun statusColorFor(status: String): Color = when (status.uppercase()) {
    "COMPLETED", "PASSED", "INSTALLED", "VERIFIED", "VERIFIED_CONNECTED", "READY", "SYNCED", "SUCCESS", "BUILT_IN" -> StudioEmerald
    "RUNNING", "TESTING", "DOWNLOADING", "DOWNLOADED", "VERIFYING", "LOADING", "PROCESSING", "TRANSLATED", "GENERATED", "RETRIED" -> StudioCyan
    "WARNING", "PARTIAL", "CANCELLED", "SKIPPED", "UNSUPPORTED", "PENDING", "WAITING", "PAUSED_CHECKPOINT",
    "UNSUPPORTED_ON_ANDROID", "NOT_CONFIGURED", "KEY_SAVED_UNVERIFIED", "API_KEY_SAVED_UNVERIFIED" -> StudioAmber
    "FAILED", "ERROR", "CORRUPTED", "BLOCKED", "AUTH_FAILED", "QUOTA_OR_RATE_LIMITED",
    "NETWORK_UNAVAILABLE", "MODEL_UNAVAILABLE", "VERIFICATION_FAILED" -> StudioCrimson
    else -> StudioTextMuted
}

@Composable
fun StudioSectionHeader(
    title: String,
    subtitle: String? = null,
    trailing: @Composable (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = StudioTextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = StudioTextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (trailing != null) {
            Spacer(modifier = Modifier.width(8.dp))
            trailing()
        }
    }
}

@Composable
fun StudioEmptyState(
    icon: ImageVector,
    title: String,
    description: String,
    primaryActionLabel: String? = null,
    onPrimaryAction: (() -> Unit)? = null,
    secondaryActionLabel: String? = null,
    onSecondaryAction: (() -> Unit)? = null,
    testTagPrefix: String = "empty_state"
) {
    StudioCard(
        modifier = Modifier.testTag("${testTagPrefix}_card"),
        padding = 24.dp
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(StudioSurfaceElevated)
                    .border(1.dp, StudioBorder, RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = StudioViolet,
                    modifier = Modifier.size(26.dp)
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = StudioTextPrimary,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = StudioTextSecondary,
                textAlign = TextAlign.Center
            )
            if (primaryActionLabel != null && onPrimaryAction != null) {
                Spacer(modifier = Modifier.height(20.dp))
                Button(
                    onClick = onPrimaryAction,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("${testTagPrefix}_primary_btn"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = StudioViolet,
                        contentColor = Color(0xFF0A0B0F)
                    ),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)
                ) {
                    Text(primaryActionLabel, style = MaterialTheme.typography.labelLarge)
                }
            }
            if (secondaryActionLabel != null && onSecondaryAction != null) {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onSecondaryAction,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("${testTagPrefix}_secondary_btn"),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, StudioBorder)
                ) {
                    Text(secondaryActionLabel, color = StudioTextPrimary)
                }
            }
        }
    }
}

/**
 * Compact contextual strip so the user always sees which Project, Episode, and Stage is open,
 * how many stages are complete, and whether anything needs attention.
 */
@Composable
fun ActiveContextBar(
    project: ProjectEntity?,
    episode: EpisodeEntity?,
    onSwitchProject: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (episode == null) return
    val completedCount = (1..14).count { (episode.completedStagesMask and (1 shl it)) != 0 }
    val hasError = episode.pipelineStatus in setOf("ERROR", "FAILED") || !episode.lastErrorMessage.isNullOrBlank()
    val isPartialOrCancelled = episode.pipelineStatus in setOf("PARTIAL", "CANCELLED")
    val statusColor = when {
        hasError && !isPartialOrCancelled -> StudioCrimson
        isPartialOrCancelled -> StudioAmber
        episode.pipelineStatus == "RUNNING" -> StudioCyan
        completedCount >= 14 -> StudioEmerald
        completedCount >= 13 && !hasError -> StudioEmerald
        else -> StudioAmber
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onSwitchProject),
        color = StudioSurface,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(
            1.dp,
            when {
                hasError && !isPartialOrCancelled -> StudioCrimson.copy(alpha = 0.45f)
                isPartialOrCancelled -> StudioAmber.copy(alpha = 0.45f)
                else -> StudioBorderSubtle
            }
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${project?.title ?: "Project"} • Ep ${episode.episodeNumber}",
                    style = MaterialTheme.typography.labelLarge,
                    color = StudioTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = episode.currentStageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (hasError) StudioCrimson else StudioTextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = when {
                        hasError -> Icons.Default.ErrorOutline
                        completedCount >= 13 -> Icons.Default.CheckCircle
                        else -> Icons.Default.Schedule
                    },
                    contentDescription = "Stage Status",
                    tint = statusColor,
                    modifier = Modifier.size(16.dp)
                )
                StatusPill(text = "$completedCount/14", color = statusColor)
            }
        }
    }
}

/**
 * Honest PCM Waveform Canvas.
 * Renders real PCM amplitude bars extracted strictly from [audioFilePath].
 * Never draws synthetic sine waves if no real audio file exists.
 */
@Composable
fun AudioWaveformCanvas(
    audioFilePath: String?,
    accentColor: Color,
    modifier: Modifier = Modifier,
    barCount: Int = 56,
    playbackProgress: Float = 0f
) {
    val peaks = remember(audioFilePath, barCount) {
        if (audioFilePath.isNullOrBlank()) {
            emptyList()
        } else {
            AudioDspEngine.extractWaveformPeaks(File(audioFilePath), barCount)
        }
    }

    if (peaks.isEmpty()) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(40.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(StudioSurfaceElevated.copy(alpha = 0.55f))
                .border(1.dp, StudioBorderSubtle, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxWidth().height(1.dp)) {
                drawLine(
                    color = StudioBorder,
                    start = Offset(12f, 0f),
                    end = Offset(size.width - 12f, 0f),
                    strokeWidth = 1.5f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f)
                )
            }
            Text(
                text = "No audio waveform",
                style = MaterialTheme.typography.labelSmall,
                color = StudioTextMuted,
                modifier = Modifier
                    .background(StudioSurfaceElevated)
                    .padding(horizontal = 8.dp)
            )
        }
        return
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(StudioSurfaceElevated.copy(alpha = 0.7f))
    ) {
        val count = peaks.size
        val barWidth = (size.width / count).coerceAtLeast(2f)
        val gap = (barWidth * 0.28f).coerceAtLeast(1f)
        val drawWidth = (barWidth - gap).coerceAtLeast(1.5f)
        val midY = size.height / 2f

        for (i in 0 until count) {
            val amp = peaks[i].coerceIn(0.05f, 1.0f)
            val barH = (amp * size.height * 0.84f).coerceAtLeast(3f)
            val x = i * barWidth + gap / 2f
            val frac = i.toFloat() / count.toFloat()
            val color = if (playbackProgress > 0f && frac <= playbackProgress) {
                accentColor
            } else {
                accentColor.copy(alpha = 0.42f)
            }
            drawRoundRect(
                color = color,
                topLeft = Offset(x, midY - barH / 2f),
                size = Size(drawWidth, barH),
                cornerRadius = CornerRadius(2f, 2f)
            )
        }
    }
}

@Composable
fun AudioPlayPauseButton(
    filePath: String?,
    label: String,
    playbackState: AudioPlaybackState,
    onTogglePlay: (String, String) -> Unit,
    accentColor: Color = StudioViolet,
    modifier: Modifier = Modifier
) {
    val fileExists = remember(filePath) {
        !filePath.isNullOrBlank() && File(filePath).exists() && File(filePath).length() > 44L
    }
    val isThisPlaying = playbackState.isPlaying && playbackState.activeFilePath == filePath

    IconButton(
        onClick = {
            if (fileExists && !filePath.isNullOrBlank()) {
                onTogglePlay(filePath, label)
            }
        },
        enabled = fileExists,
        modifier = modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(
                when {
                    !fileExists -> StudioSurfaceElevated.copy(alpha = 0.5f)
                    isThisPlaying -> accentColor.copy(alpha = 0.22f)
                    else -> StudioSurfaceElevated
                }
            )
            .border(
                1.dp,
                if (isThisPlaying) accentColor else StudioBorderSubtle,
                CircleShape
            )
    ) {
        Icon(
            imageVector = if (isThisPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
            contentDescription = if (isThisPlaying) "Pause $label" else "Play $label",
            tint = if (fileExists) accentColor else StudioTextMuted,
            modifier = Modifier.size(22.dp)
        )
    }
}

@Composable
fun MetadataChipRow(
    items: List<Pair<String, String>>,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items.forEach { (label, value) ->
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(StudioSurfaceElevated)
                    .border(1.dp, StudioBorderSubtle, RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = StudioTextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = value,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontFamily = JetBrainsMonoFamily,
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = StudioTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun WarningBanner(
    message: String,
    isError: Boolean = false,
    modifier: Modifier = Modifier
) {
    val color = if (isError) StudioCrimson else StudioAmber
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            imageVector = if (isError) Icons.Default.ErrorOutline else Icons.Default.WarningAmber,
            contentDescription = if (isError) "Error" else "Notice",
            tint = color,
            modifier = Modifier.size(18.dp)
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = StudioTextPrimary,
            modifier = Modifier.weight(1f)
        )
    }
}
