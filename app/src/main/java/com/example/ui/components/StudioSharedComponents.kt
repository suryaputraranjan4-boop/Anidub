package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.engine.AudioDspEngine
import com.example.engine.AudioPlaybackState
import com.example.ui.theme.StudioAmber
import com.example.ui.theme.StudioAmberContainer
import com.example.ui.theme.StudioBorder
import com.example.ui.theme.StudioCrimson
import com.example.ui.theme.StudioCrimsonContainer
import com.example.ui.theme.StudioCyan
import com.example.ui.theme.StudioCyanContainer
import com.example.ui.theme.StudioEmerald
import com.example.ui.theme.StudioEmeraldContainer
import com.example.ui.theme.StudioSurfaceElevated
import com.example.ui.theme.StudioTextMuted
import com.example.ui.theme.StudioTextPrimary
import com.example.ui.theme.StudioTextSecondary
import com.example.ui.theme.StudioViolet
import com.example.ui.theme.StudioVioletContainer
import com.example.viewmodel.PipelineBannerState
import java.io.File

/**
 * Formats milliseconds into exact studio timecode: MM:SS.mmm (e.g., 00:05.000).
 */
fun formatTimecodeMs(ms: Long): String {
    val safeMs = ms.coerceAtLeast(0L)
    val minutes = (safeMs / 60000L)
    val seconds = (safeMs % 60000L) / 1000L
    val millis = safeMs % 1000L
    return "%02d:%02d.%03d".format(minutes, seconds, millis)
}

@Composable
fun StudioSectionHeader(
    title: String,
    subtitle: String? = null,
    badgeText: String? = null,
    badgeColor: Color = StudioViolet
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = StudioTextPrimary,
                modifier = Modifier.weight(1f)
            )
            if (!badgeText.isNullOrBlank()) {
                Surface(
                    color = badgeColor.copy(alpha = 0.16f),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, badgeColor.copy(alpha = 0.45f))
                ) {
                    Text(
                        text = badgeText,
                        style = MaterialTheme.typography.labelMedium,
                        color = badgeColor,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }
        }
        if (!subtitle.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = StudioTextSecondary
            )
        }
    }
}

@Composable
fun RuntimeCompatibilityBadge(runtimeType: String) {
    val (bg, fg, label) = when (runtimeType) {
        "ANDROID_LOCAL" -> Triple(StudioEmeraldContainer, StudioEmerald, "ANDROID LOCAL DSP")
        "ONNX_ANDROID" -> Triple(StudioCyanContainer, StudioCyan, "ONNX ANDROID")
        "NATIVE_CPP_DSP" -> Triple(StudioEmeraldContainer, StudioEmerald, "NATIVE C++ / PCM")
        "EXTERNAL_API" -> Triple(StudioVioletContainer, StudioViolet, "FIREBASE AI LOGIC")
        else -> Triple(StudioAmberContainer, StudioAmber, "REMOTE GPU WORKER")
    }
    Surface(
        color = bg,
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, fg.copy(alpha = 0.4f))
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = fg,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

@Composable
fun AudioWaveformCanvas(
    audioFilePath: String?,
    accentColor: Color = StudioViolet,
    height: Dp = 52.dp,
    barCount: Int = 44,
    progressFraction: Float = 0f,
    onSeekFraction: ((Float) -> Unit)? = null
) {
    val peaks = remember(audioFilePath, barCount) {
        if (audioFilePath.isNullOrBlank()) {
            List(barCount) { i -> 0.18f + 0.45f * kotlin.math.abs(kotlin.math.sin(i * 0.45f)) }
        } else {
            AudioDspEngine.extractWaveformPeaks(File(audioFilePath), barCount)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .background(Color(0xFF0E1019), RoundedCornerShape(8.dp))
            .then(
                if (onSeekFraction != null) {
                    Modifier.pointerInput(Unit) {
                        detectTapGestures { offset ->
                            if (size.width > 0) {
                                onSeekFraction((offset.x / size.width).coerceIn(0f, 1f))
                            }
                        }
                    }
                } else Modifier
            )
            .padding(horizontal = 6.dp, vertical = 6.dp)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val count = peaks.size.coerceAtLeast(1)
            val slotWidth = w / count
            val barWidth = (slotWidth * 0.64f).coerceAtLeast(2f)

            // Center line
            drawLine(
                color = StudioBorder.copy(alpha = 0.5f),
                start = Offset(0f, h / 2f),
                end = Offset(w, h / 2f),
                strokeWidth = 1f
            )

            for (i in peaks.indices) {
                val frac = i.toFloat() / count
                val amp = peaks[i].coerceIn(0.08f, 1.0f)
                val barH = (h * amp).coerceAtLeast(4f)
                val x = i * slotWidth + (slotWidth - barWidth) / 2f
                val y = (h - barH) / 2f
                val color = if (progressFraction > 0f && frac <= progressFraction) {
                    StudioCyan
                } else {
                    accentColor.copy(alpha = 0.82f)
                }
                drawRoundRect(
                    color = color,
                    topLeft = Offset(x, y),
                    size = Size(barWidth, barH),
                    cornerRadius = CornerRadius(2f, 2f)
                )
            }

            if (progressFraction in 0.001f..0.999f) {
                val playheadX = w * progressFraction
                drawLine(
                    color = Color.White,
                    start = Offset(playheadX, 0f),
                    end = Offset(playheadX, h),
                    strokeWidth = 3f
                )
            }
        }
    }
}

@Composable
fun AudioPlayPreviewButton(
    filePath: String?,
    label: String,
    playbackState: AudioPlaybackState,
    onPlayClick: (String, String) -> Unit,
    onStopClick: () -> Unit,
    modifier: Modifier = Modifier,
    accentColor: Color = StudioViolet
) {
    val isThisPlaying = playbackState.isPlaying && playbackState.activeFilePath == filePath && !filePath.isNullOrBlank()
    val fileExists = remember(filePath) { !filePath.isNullOrBlank() && File(filePath).exists() }

    OutlinedButton(
        onClick = {
            if (isThisPlaying) {
                onStopClick()
            } else if (!filePath.isNullOrBlank()) {
                onPlayClick(filePath, label)
            }
        },
        enabled = fileExists,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, if (isThisPlaying) StudioCyan else accentColor.copy(alpha = 0.6f)),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (isThisPlaying) StudioCyanContainer else accentColor.copy(alpha = 0.10f),
            contentColor = if (isThisPlaying) StudioCyan else StudioTextPrimary
        ),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        modifier = modifier.height(38.dp).testTag("audio_preview_${label.take(12).replace(" ", "_")}")
    ) {
        Icon(
            imageVector = if (isThisPlaying) Icons.Default.Stop else Icons.Default.PlayArrow,
            contentDescription = if (isThisPlaying) "Stop $label" else "Preview $label",
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = if (isThisPlaying) {
                "Stop (${formatTimecodeMs(playbackState.currentPositionMs)})"
            } else {
                label
            },
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun PipelineLiveStatusBanner(
    bannerState: PipelineBannerState,
    playbackState: AudioPlaybackState,
    onStopAudio: () -> Unit,
    onDismissBanner: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        if (bannerState.isRunning) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                colors = CardDefaults.cardColors(containerColor = StudioVioletContainer),
                border = BorderStroke(1.dp, StudioViolet)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = StudioCyan
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = bannerState.activeStageTitle,
                            style = MaterialTheme.typography.titleSmall,
                            color = StudioTextPrimary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = bannerState.activeDetail,
                        style = MaterialTheme.typography.bodySmall,
                        color = StudioTextSecondary
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { (bannerState.activeStageNumber.coerceIn(1, 14)) / 14f },
                        modifier = Modifier.fillMaxWidth().height(5.dp),
                        color = StudioCyan,
                        trackColor = StudioSurfaceElevated
                    )
                }
            }
        } else if (!bannerState.statusBannerMessage.isNullOrBlank()) {
            val isErr = bannerState.isError
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isErr) StudioCrimsonContainer else StudioEmeraldContainer
                ),
                border = BorderStroke(1.dp, if (isErr) StudioCrimson else StudioEmerald)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (isErr) Icons.Default.Error else Icons.Default.CheckCircle,
                        contentDescription = "Status",
                        tint = if (isErr) StudioCrimson else StudioEmerald,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = bannerState.statusBannerMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = StudioTextPrimary,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDismissBanner, modifier = Modifier.size(28.dp)) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Dismiss",
                            tint = StudioTextSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }

        if (playbackState.isPlaying) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                colors = CardDefaults.cardColors(containerColor = StudioCyanContainer),
                border = BorderStroke(1.dp, StudioCyan)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Sync,
                        contentDescription = "Playing",
                        tint = StudioCyan,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Playing: ${playbackState.activeLabel}  •  ${formatTimecodeMs(playbackState.currentPositionMs)} / ${formatTimecodeMs(playbackState.totalDurationMs)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = StudioTextPrimary,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "STOP",
                        style = MaterialTheme.typography.labelMedium,
                        color = StudioCyan,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable { onStopAudio() }.padding(4.dp)
                    )
                }
            }
        }
    }
}
