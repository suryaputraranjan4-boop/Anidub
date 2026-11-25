package com.example.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.UploadFile
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.local.AgentJobRecordEntity
import com.example.data.local.DialogueSegmentEntity
import com.example.data.local.EpisodeEntity
import com.example.data.local.ModelManifestEntity
import com.example.data.local.ProjectEntity
import com.example.engine.AudioPlaybackState
import com.example.ui.components.AudioPlayPreviewButton
import com.example.ui.components.AudioWaveformCanvas
import com.example.ui.components.StudioSectionHeader
import com.example.ui.components.formatTimecodeMs
import com.example.ui.theme.StudioAmber
import com.example.ui.theme.StudioBackground
import com.example.ui.theme.StudioBorder
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
import kotlinx.coroutines.delay

/**
 * Screen 1 — Splash Screen
 */
@Composable
fun SplashScreen(
    onboardingCompleted: Boolean,
    onSplashFinished: (Boolean) -> Unit
) {
    LaunchedEffect(onboardingCompleted) {
        delay(950L)
        onSplashFinished(onboardingCompleted)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(StudioBackground, StudioVioletContainer, StudioBackground)
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(28.dp)
        ) {
            Surface(
                shape = CircleShape,
                color = StudioViolet.copy(alpha = 0.18f),
                border = BorderStroke(2.dp, StudioViolet),
                modifier = Modifier.size(92.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.GraphicEq,
                        contentDescription = "AnimeDub Studio Logo",
                        tint = StudioCyan,
                        modifier = Modifier.size(48.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = "ANIMEDUB STUDIO",
                style = MaterialTheme.typography.displayMedium,
                color = StudioTextPrimary,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "7-Agent Japanese → Roman Hinglish Production Suite",
                style = MaterialTheme.typography.bodyMedium,
                color = StudioCyan
            )
            Spacer(modifier = Modifier.height(24.dp))
            LinearProgressIndicator(
                modifier = Modifier.width(180.dp).height(4.dp),
                color = StudioViolet,
                trackColor = StudioSurfaceElevated
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "Initializing Room DB • 16-bit PCM DSP • Firebase AI Logic...",
                style = MaterialTheme.typography.labelSmall,
                color = StudioTextMuted
            )
        }
    }
}

/**
 * Screen 2 — Onboarding Screen
 */
@Composable
fun OnboardingScreen(
    onFinishOnboarding: () -> Unit
) {
    var step by remember { mutableIntStateOf(0) }
    val pages = listOf(
        Triple(
            "7 Specialist Agents — Deterministic Pipeline",
            "No Master LLM or fake chatbot. Seven dedicated agents handle Background Stem Separation (Agent 6), Japanese ASR (Agent 1), Dialogue Timestamps (Agent 2), Audio-Only Speaker Analysis (Agent 3), Gemini Roman Hinglish Translation (Agent 4), Voice Cloning Batches (Agent 5), and Timeline Mixing & 13-Point QC (Agent 7).",
            "STAGE 1 → STAGE 14 DETERMINISTIC STATE MACHINE"
        ),
        Triple(
            "100% Audio-Only Speaker Identification",
            "Zero visual character recognition and zero facial recognition. Agent 3 identifies recurring characters strictly from vocal F0 pitch range, spectral centroid, timbre, speaking speed, and 16-D harmonic speaker embeddings, assigning stable labels A, B, C by first occurrence.",
            "AUDIO-FIRST SPEAKER DIARIZATION GUARANTEE"
        ),
        Triple(
            "Global Sequential IDs + Character-Wise Batches",
            "Dialogues retain one global sequential counter (A-001, B-002, C-003, A-004, B-005, A-006, C-007). Voice synthesis executes in character-wise batches: Batch 1 (A-001, A-004, A-006) → Batch 2 (B-002, B-005) → Batch 3 (C-003, C-007), then restores exact original chronological timestamps.",
            "A-001 → B-002 → C-003 → A-004 → B-005 → A-006 → C-007"
        )
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(StudioBackground)
            .padding(24.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "STUDIO ARCHITECTURE BRIEF",
                style = MaterialTheme.typography.labelLarge,
                color = StudioViolet
            )
            Text(
                text = "Skip",
                style = MaterialTheme.typography.labelLarge,
                color = StudioTextSecondary,
                modifier = Modifier
                    .clickable { onFinishOnboarding() }
                    .padding(8.dp)
                    .testTag("onboarding_skip_btn")
            )
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = StudioSurface),
            border = BorderStroke(1.dp, StudioBorder),
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Surface(
                    color = StudioCyanContainer,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = pages[step].third,
                        style = MaterialTheme.typography.labelSmall,
                        color = StudioCyan,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = pages[step].first,
                    style = MaterialTheme.typography.headlineMedium,
                    color = StudioTextPrimary
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = pages[step].second,
                    style = MaterialTheme.typography.bodyLarge,
                    color = StudioTextSecondary
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Step ${step + 1} of ${pages.size}",
                style = MaterialTheme.typography.labelMedium,
                color = StudioTextMuted
            )
            Button(
                onClick = {
                    if (step < pages.size - 1) step++ else onFinishOnboarding()
                },
                colors = ButtonDefaults.buttonColors(containerColor = StudioViolet),
                modifier = Modifier.testTag("onboarding_next_btn")
            ) {
                Text(if (step < pages.size - 1) "Next Architecture Pillar" else "Enter AnimeDub Studio")
                Spacer(modifier = Modifier.width(6.dp))
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(16.dp))
            }
        }
    }
}

/**
 * Screen 3 — Production Dashboard
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DashboardScreen(
    projects: List<ProjectEntity>,
    activeEpisode: EpisodeEntity?,
    dialogues: List<DialogueSegmentEntity>,
    models: List<ModelManifestEntity>,
    recentJobs: List<AgentJobRecordEntity>,
    storageUsedBytes: Long,
    storageFreeBytes: Long,
    onNavigate: (StudioScreen) -> Unit,
    onRunFullPipeline: () -> Unit
) {
    val installedModelsCount = models.count { it.downloadState == "INSTALLED" }
    val generatedDialoguesCount = dialogues.count { !it.generatedAudioPath.isNullOrBlank() }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Spacer(modifier = Modifier.height(4.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = StudioSurface),
                border = BorderStroke(1.dp, StudioViolet.copy(alpha = 0.5f)),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .background(
                            Brush.horizontalGradient(
                                listOf(StudioVioletContainer.copy(alpha = 0.65f), StudioSurface)
                            )
                        )
                        .padding(18.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "ACTIVE PRODUCTION CONSOLE",
                                style = MaterialTheme.typography.labelSmall,
                                color = StudioCyan
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = activeEpisode?.episodeTitle ?: "No Episode Loaded",
                                style = MaterialTheme.typography.headlineSmall,
                                color = StudioTextPrimary
                            )
                            Text(
                                text = activeEpisode?.currentStageName ?: "Stage 1 — Ready",
                                style = MaterialTheme.typography.bodySmall,
                                color = StudioEmerald
                            )
                        }
                        Button(
                            onClick = onRunFullPipeline,
                            colors = ButtonDefaults.buttonColors(containerColor = StudioViolet),
                            modifier = Modifier.testTag("dashboard_run_pipeline_btn")
                        ) {
                            Icon(Icons.Default.PlayCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Run All 14 Stages")
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    LinearProgressIndicator(
                        progress = { ((activeEpisode?.currentStageNumber ?: 1).coerceIn(1, 14)) / 14f },
                        modifier = Modifier.fillMaxWidth().height(6.dp),
                        color = StudioCyan,
                        trackColor = StudioSurfaceElevated
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Stage ${activeEpisode?.currentStageNumber ?: 1} / 14 Completed",
                            style = MaterialTheme.typography.labelSmall,
                            color = StudioTextSecondary
                        )
                        Text(
                            text = "$generatedDialoguesCount / ${dialogues.size} Dubbed Lines Synced",
                            style = MaterialTheme.typography.labelSmall,
                            color = StudioCyan
                        )
                    }
                }
            }
        }

        // Key Studio Telemetry Metrics Grid
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                MetricStatCard(
                    title = "PROJECTS",
                    value = "${projects.size} Active",
                    subtitle = "Last: Ep ${activeEpisode?.episodeNumber ?: 1}",
                    accent = StudioViolet,
                    modifier = Modifier.weight(1f).clickable { onNavigate(StudioScreen.PROJECT_LIBRARY) }
                )
                MetricStatCard(
                    title = "AI MODELS",
                    value = "$installedModelsCount / ${models.size}",
                    subtitle = "Verified Ready",
                    accent = StudioCyan,
                    modifier = Modifier.weight(1f).clickable { onNavigate(StudioScreen.MODEL_MANAGER) }
                )
                MetricStatCard(
                    title = "STORAGE",
                    value = "${(storageUsedBytes / 1024).coerceAtLeast(320)} KB",
                    subtitle = "${storageFreeBytes / (1024 * 1024)} MB Free",
                    accent = StudioEmerald,
                    modifier = Modifier.weight(1f).clickable { onNavigate(StudioScreen.MEDIA_INFORMATION) }
                )
            }
        }

        // Complete 28-Screen Studio Suite Directory for instant one-tap access
        item {
            StudioSectionHeader(
                title = "Studio Production Modules (All 28 Workstations)",
                subtitle = "Jump directly to any specialist agent studio, batch queue, timeline, or model tool",
                badgeText = "7 AGENTS ACTIVE"
            )
            Spacer(modifier = Modifier.height(6.dp))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val quickScreens = listOf(
                    StudioScreen.NEW_PROJECT to Icons.Default.Add,
                    StudioScreen.PROJECT_LIBRARY to Icons.Default.Folder,
                    StudioScreen.VIDEO_UPLOAD to Icons.Default.UploadFile,
                    StudioScreen.MEDIA_INFORMATION to Icons.Default.Info,
                    StudioScreen.PROCESSING_CENTER to Icons.Default.GraphicEq,
                    StudioScreen.AGENT_PROCESSING_DETAILS to Icons.Default.Verified,
                    StudioScreen.BACKGROUND_AUDIO_STUDIO to Icons.Default.LibraryMusic,
                    StudioScreen.AUDIO_STEM_MANAGER to Icons.Default.Audiotrack,
                    StudioScreen.JAPANESE_TRANSCRIPT_STUDIO to Icons.Default.Subtitles,
                    StudioScreen.DIALOGUE_SEGMENTATION_EDITOR to Icons.Default.Movie,
                    StudioScreen.SPEAKER_LIBRARY to Icons.Default.RecordVoiceOver,
                    StudioScreen.CHARACTER_VOICE_PROFILES to Icons.Default.Mic,
                    StudioScreen.VOICE_ANALYSIS_DETAILS to Icons.Default.GraphicEq,
                    StudioScreen.TRANSLATION_STUDIO to Icons.Default.Translate,
                    StudioScreen.GLOBAL_DICTIONARY to Icons.Default.Folder,
                    StudioScreen.ANIME_DICTIONARY to Icons.Default.Folder,
                    StudioScreen.VOICE_GENERATION_STUDIO to Icons.Default.Mic,
                    StudioScreen.CHARACTER_WISE_GENERATION_QUEUE to Icons.Default.PlayCircle,
                    StudioScreen.MULTITRACK_TIMELINE_EDITOR to Icons.Default.Audiotrack,
                    StudioScreen.QUALITY_CONTROL_CENTER to Icons.Default.CheckCircle,
                    StudioScreen.FINAL_EXPORT_STUDIO to Icons.Default.Movie,
                    StudioScreen.MODEL_MANAGER to Icons.Default.CloudDownload,
                    StudioScreen.PROJECT_PROCESSING_HISTORY to Icons.Default.History,
                    StudioScreen.SETTINGS to Icons.Default.Settings
                )
                quickScreens.forEach { (scr, icon) ->
                    Surface(
                        color = StudioSurfaceElevated,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, StudioBorder),
                        modifier = Modifier
                            .clickable { onNavigate(scr) }
                            .testTag("nav_chip_${scr.name.lowercase()}")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 11.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(icon, contentDescription = null, tint = StudioViolet, modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = scr.title,
                                style = MaterialTheme.typography.labelMedium,
                                color = StudioTextPrimary
                            )
                        }
                    }
                }
            }
        }

        // Mandatory Chronological vs Character-Wise Batch Preview Card
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = StudioSurface),
                border = BorderStroke(1.dp, StudioBorder),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "MANDATORY GLOBAL ID & CHARACTER-WISE BATCH STATUS",
                        style = MaterialTheme.typography.labelMedium,
                        color = StudioCyan
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Chronological Timeline: " + dialogues.joinToString(" → ") { it.dialogueId },
                        style = MaterialTheme.typography.labelLarge,
                        color = StudioTextPrimary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    val bySpeaker = dialogues.groupBy { it.speakerLabel }.toSortedMap()
                    bySpeaker.forEach { (sp, list) ->
                        Text(
                            text = "Character $sp Batch: ${list.sortedBy { it.globalOrderNumber }.joinToString(", ") { it.dialogueId }}",
                            style = MaterialTheme.typography.bodySmall,
                            color = StudioTextSecondary
                        )
                    }
                }
            }
        }

        // Active / Recent Processing Jobs
        item {
            StudioSectionHeader(
                title = "Recent Specialist Agent Activity",
                subtitle = "Real checkpoint executions recorded in Room DB",
                badgeText = "${recentJobs.size} JOBS"
            )
        }

        items(recentJobs.take(6)) { job ->
            Card(
                colors = CardDefaults.cardColors(containerColor = StudioSurface),
                border = BorderStroke(1.dp, StudioBorder),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        color = StudioEmeraldContainer,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = "S${job.stageNumber}",
                            style = MaterialTheme.typography.labelMedium,
                            color = StudioEmerald,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = job.agentName, style = MaterialTheme.typography.titleSmall, color = StudioTextPrimary)
                        Text(text = job.currentOperation, style = MaterialTheme.typography.bodySmall, color = StudioTextSecondary)
                        Text(text = job.outputSummary, style = MaterialTheme.typography.labelSmall, color = StudioCyan)
                    }
                }
            }
        }

        item { Spacer(modifier = Modifier.height(24.dp)) }
    }
}

@Composable
private fun MetricStatCard(
    title: String,
    value: String,
    subtitle: String,
    accent: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = StudioSurface),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.45f)),
        shape = RoundedCornerShape(12.dp),
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(text = title, style = MaterialTheme.typography.labelSmall, color = accent)
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = value, style = MaterialTheme.typography.titleMedium, color = StudioTextPrimary, fontWeight = FontWeight.Bold)
            Text(text = subtitle, style = MaterialTheme.typography.labelSmall, color = StudioTextMuted)
        }
    }
}

/**
 * Screen 4 — Project Library
 */
@Composable
fun ProjectLibraryScreen(
    projects: List<ProjectEntity>,
    episodes: List<EpisodeEntity>,
    activeEpisodeId: Long,
    onSelectEpisode: (Long, Long) -> Unit,
    onCreateNewProjectClick: () -> Unit,
    onOpenProcessingCenter: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                StudioSectionHeader(
                    title = "Anime Dubbing Project Library",
                    subtitle = "Persistent projects, speaker voice profiles & episode checkpoints"
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = onCreateNewProjectClick,
                colors = ButtonDefaults.buttonColors(containerColor = StudioViolet),
                modifier = Modifier.fillMaxWidth().testTag("project_library_new_btn")
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Create New Anime Dubbing Project")
            }
        }

        items(projects) { project ->
            val projectEpisodes = episodes.filter { it.projectId == project.id }
            Card(
                colors = CardDefaults.cardColors(containerColor = StudioSurface),
                border = BorderStroke(1.dp, StudioBorder),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(text = project.title, style = MaterialTheme.typography.headlineSmall, color = StudioTextPrimary)
                    Text(
                        text = "Series: ${project.animeSeriesName}  •  ${project.sourceLanguage} → ${project.targetLanguage}",
                        style = MaterialTheme.typography.bodySmall,
                        color = StudioCyan
                    )
                    Text(
                        text = "Master Sample Rate: ${project.masterSampleRateHz} Hz  •  Default TTS: ${project.preferredTtsEngine}",
                        style = MaterialTheme.typography.labelSmall,
                        color = StudioTextSecondary
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    projectEpisodes.forEach { ep ->
                        val isSelected = ep.id == activeEpisodeId
                        Surface(
                            color = if (isSelected) StudioVioletContainer else StudioSurfaceElevated,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, if (isSelected) StudioViolet else StudioBorder),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clickable {
                                    onSelectEpisode(ep.id, project.id)
                                    onOpenProcessingCenter()
                                }
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(text = ep.episodeTitle, style = MaterialTheme.typography.titleSmall, color = StudioTextPrimary)
                                    Text(
                                        text = "${ep.currentStageName} • ${ep.resolution} • ${ep.durationMs / 1000}s",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = StudioEmerald
                                    )
                                }
                                Text(
                                    text = if (isSelected) "ACTIVE" else "LOAD",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (isSelected) StudioCyan else StudioViolet
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Screen 5 — New Project Screen
 */
@Composable
fun NewProjectScreen(
    onCreateProject: (title: String, animeSeries: String, sampleRateHz: Int, ttsEngine: String) -> Unit
) {
    var title by remember { mutableStateOf("Shadow Guild Chronicles — Hindi Dub") }
    var series by remember { mutableStateOf("Shadow Guild Chronicles") }
    var sampleRate by remember { mutableIntStateOf(24000) }
    var ttsEngine by remember { mutableStateOf("XTTS-v2") }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            StudioSectionHeader(
                title = "Create New Anime Dubbing Project",
                subtitle = "Configures persistent anime dictionary scope, reusable speaker embeddings & lossless audio timebase",
                badgeText = "JA → HI-LATN"
            )
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = StudioSurface),
                border = BorderStroke(1.dp, StudioBorder),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text("Project Production Title") },
                        modifier = Modifier.fillMaxWidth().testTag("new_project_title_input")
                    )
                    OutlinedTextField(
                        value = series,
                        onValueChange = { series = it },
                        label = { Text("Anime Series Name (For Anime-Specific Dictionary & Recurring Voices)") },
                        modifier = Modifier.fillMaxWidth().testTag("new_project_series_input")
                    )

                    Text("Master Audio Timebase Sample Rate", style = MaterialTheme.typography.titleSmall, color = StudioTextPrimary)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        listOf(24000, 44100, 48000).forEach { sr ->
                            FilterChip(
                                selected = sampleRate == sr,
                                onClick = { sampleRate = sr },
                                label = { Text("${sr / 1000} kHz PCM") }
                            )
                        }
                    }

                    Text("Primary Voice Cloning / TTS Synthesis Engine", style = MaterialTheme.typography.titleSmall, color = StudioTextPrimary)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(
                            "XTTS-v2" to "Coqui XTTS-v2 (Cross-Language Japanese Reference → Hindi/Hinglish)",
                            "F5-TTS-Hindi" to "SPRINGLab F5-TTS Hindi 24KHz (Research Baseline)",
                            "Chatterbox-V3-Exp" to "Chatterbox Multilingual V3 (Archived Research Benchmark)"
                        ).forEach { (id, desc) ->
                            FilterChip(
                                selected = ttsEngine == id,
                                onClick = { ttsEngine = id },
                                label = { Text(desc) }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = { onCreateProject(title, series, sampleRate, ttsEngine) },
                        colors = ButtonDefaults.buttonColors(containerColor = StudioViolet),
                        modifier = Modifier.fillMaxWidth().testTag("create_project_submit_btn")
                    ) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Initialize Project Workspace & Episode 1")
                    }
                }
            }
        }
    }
}

/**
 * Screen 6 — Video Upload Studio (Stage 1)
 */
@Composable
fun VideoUploadScreen(
    activeEpisode: EpisodeEntity?,
    playbackState: AudioPlaybackState,
    onMediaUriSelected: (android.net.Uri) -> Unit,
    onPlayAudio: (String, String) -> Unit,
    onStopAudio: () -> Unit,
    onProceedToMediaInfo: () -> Unit,
    onRunPipeline: () -> Unit
) {
    val mediaPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            onMediaUriSelected(uri)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            StudioSectionHeader(
                title = "Stage 1 — Episode Media Upload",
                subtitle = "Select an anime video/audio file from device storage or inspect the active 7-dialogue Japanese episode master",
                badgeText = "STAGE 1"
            )
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = StudioSurface),
                border = BorderStroke(1.dp, StudioViolet),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.UploadFile,
                        contentDescription = "Upload Video",
                        tint = StudioCyan,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Select Anime Episode Video (MP4 / MKV / MOV / WAV)",
                        style = MaterialTheme.typography.titleMedium,
                        color = StudioTextPrimary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Uses Android zero-permission system document picker. Original video and audio master are preserved losslessly.",
                        style = MaterialTheme.typography.bodySmall,
                        color = StudioTextSecondary
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Button(
                        onClick = { mediaPickerLauncher.launch("*/*") },
                        colors = ButtonDefaults.buttonColors(containerColor = StudioViolet),
                        modifier = Modifier.fillMaxWidth().testTag("upload_pick_media_btn")
                    ) {
                        Icon(Icons.Default.UploadFile, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Browse Device for Anime Video / Audio")
                    }
                }
            }
        }

        if (activeEpisode != null) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = StudioSurface),
                    border = BorderStroke(1.dp, StudioBorder),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "LOADED EPISODE SOURCE MASTER",
                            style = MaterialTheme.typography.labelSmall,
                            color = StudioEmerald
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(text = activeEpisode.videoFileName, style = MaterialTheme.typography.titleMedium, color = StudioTextPrimary)
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Duration: ${formatTimecodeMs(activeEpisode.durationMs)}  •  Resolution: ${activeEpisode.resolution}  •  FPS: ${activeEpisode.frameRateFps}",
                            style = MaterialTheme.typography.bodySmall,
                            color = StudioTextSecondary
                        )
                        Text(
                            text = "Audio: ${activeEpisode.audioCodec} @ ${activeEpisode.audioSampleRateHz} Hz (${activeEpisode.audioChannels} ch)  •  Size: ${activeEpisode.fileSizeBytes / 1024} KB",
                            style = MaterialTheme.typography.labelSmall,
                            color = StudioCyan
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        AudioWaveformCanvas(
                            audioFilePath = activeEpisode.originalMasterAudioPath,
                            accentColor = StudioCyan,
                            height = 56.dp
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            AudioPlayPreviewButton(
                                filePath = activeEpisode.originalMasterAudioPath,
                                label = "Preview Original Master",
                                playbackState = playbackState,
                                onPlayClick = onPlayAudio,
                                onStopClick = onStopAudio
                            )
                            OutlinedButton(onClick = onProceedToMediaInfo) {
                                Text("Media Details")
                            }
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        Button(
                            onClick = onRunPipeline,
                            colors = ButtonDefaults.buttonColors(containerColor = StudioEmerald),
                            modifier = Modifier.fillMaxWidth().testTag("upload_start_pipeline_btn")
                        ) {
                            Icon(Icons.Default.PlayCircle, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Execute 14-Stage Dubbing Pipeline on Episode")
                        }
                    }
                }
            }
        }
    }
}

/**
 * Screen 7 — Media Information Inspector
 */
@Composable
fun MediaInformationScreen(
    activeEpisode: EpisodeEntity?,
    playbackState: AudioPlaybackState,
    onPlayAudio: (String, String) -> Unit,
    onStopAudio: () -> Unit,
    onOpenBackgroundSeparation: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            StudioSectionHeader(
                title = "Stage 1 & 2 — Media & Timebase Metadata",
                subtitle = "Extracted media container attributes and protected 16-bit PCM audio master telemetry",
                badgeText = "LOSSLESS TIMEBASE"
            )
        }

        if (activeEpisode != null) {
            val specs = listOf(
                "Video File Name" to activeEpisode.videoFileName,
                "Episode Title" to activeEpisode.episodeTitle,
                "Duration (Exact Timebase)" to "${formatTimecodeMs(activeEpisode.durationMs)} (${activeEpisode.durationMs} ms)",
                "Video Resolution" to activeEpisode.resolution,
                "Frame Rate" to "${activeEpisode.frameRateFps} fps (NTSC Anime Film Cadence)",
                "Container File Size" to "${activeEpisode.fileSizeBytes / 1024} KB (${activeEpisode.fileSizeBytes} bytes)",
                "Audio Stream Codec" to activeEpisode.audioCodec,
                "Master Sample Rate" to "${activeEpisode.audioSampleRateHz} Hz (Lossless Intermediate WAV)",
                "Audio Channels" to "${activeEpisode.audioChannels} Channel(s) (16-bit Signed Little-Endian PCM)",
                "Original Master Location" to activeEpisode.originalMasterAudioPath
            )

            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = StudioSurface),
                    border = BorderStroke(1.dp, StudioBorder),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        specs.forEach { (k, v) ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(text = k, style = MaterialTheme.typography.bodySmall, color = StudioTextSecondary, modifier = Modifier.weight(0.42f))
                                Text(text = v, style = MaterialTheme.typography.labelMedium, color = StudioTextPrimary, modifier = Modifier.weight(0.58f))
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        AudioWaveformCanvas(
                            audioFilePath = activeEpisode.originalMasterAudioPath,
                            accentColor = StudioViolet,
                            height = 60.dp
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            AudioPlayPreviewButton(
                                filePath = activeEpisode.originalMasterAudioPath,
                                label = "Play Lossless Master",
                                playbackState = playbackState,
                                onPlayClick = onPlayAudio,
                                onStopClick = onStopAudio
                            )
                            Button(
                                onClick = onOpenBackgroundSeparation,
                                colors = ButtonDefaults.buttonColors(containerColor = StudioViolet)
                            ) {
                                Text("Next: Agent 6 Stems")
                            }
                        }
                    }
                }
            }
        }
    }
}
