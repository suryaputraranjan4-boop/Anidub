package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.components.PipelineLiveStatusBanner
import com.example.ui.screens.AgentProcessingDetailsScreen
import com.example.ui.screens.AudioStemManagerScreen
import com.example.ui.screens.BackgroundAudioStudioScreen
import com.example.ui.screens.CharacterVoiceProfilesScreen
import com.example.ui.screens.CharacterWiseGenerationQueueScreen
import com.example.ui.screens.DashboardScreen
import com.example.ui.screens.DialogueSegmentationEditorScreen
import com.example.ui.screens.DictionaryManagerScreen
import com.example.ui.screens.FinalExportStudioScreen
import com.example.ui.screens.JapaneseTranscriptStudioScreen
import com.example.ui.screens.MediaInformationScreen
import com.example.ui.screens.ModelDownloadDetailsScreen
import com.example.ui.screens.ModelManagerScreen
import com.example.ui.screens.MultitrackTimelineEditorScreen
import com.example.ui.screens.NewProjectScreen
import com.example.ui.screens.OnboardingScreen
import com.example.ui.screens.ProcessingCenterScreen
import com.example.ui.screens.ProjectLibraryScreen
import com.example.ui.screens.ProjectProcessingHistoryScreen
import com.example.ui.screens.QualityControlCenterScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.SpeakerLibraryScreen
import com.example.ui.screens.SplashScreen
import com.example.ui.screens.TranslationStudioScreen
import com.example.ui.screens.VideoUploadScreen
import com.example.ui.screens.VoiceAnalysisDetailsScreen
import com.example.ui.screens.VoiceGenerationStudioScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.StudioBackground
import com.example.ui.theme.StudioBorder
import com.example.ui.theme.StudioCyan
import com.example.ui.theme.StudioSurface
import com.example.ui.theme.StudioSurfaceElevated
import com.example.ui.theme.StudioTextPrimary
import com.example.ui.theme.StudioTextSecondary
import com.example.ui.theme.StudioViolet
import com.example.ui.theme.StudioVioletContainer
import com.example.util.configureAppCheck
import com.example.viewmodel.StudioScreen
import com.example.viewmodel.StudioViewModel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // STEP 1: Configure Firebase App Check with launch intent before any UI or Firebase AI calls
        configureAppCheck(this, intent)

        // STEP 2: Enable Edge-to-Edge layout
        enableEdgeToEdge()

        // STEP 3: Render Studio Compose UI
        setContent {
            MyApplicationTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = StudioBackground
                ) {
                    val studioViewModel: StudioViewModel = viewModel()
                    AnimeDubStudioRoot(viewModel = studioViewModel)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnimeDubStudioRoot(viewModel: StudioViewModel) {
    val currentScreen by viewModel.currentScreen.collectAsStateWithLifecycle()
    val studioConfig by viewModel.studioConfig.collectAsStateWithLifecycle()
    val projects by viewModel.projects.collectAsStateWithLifecycle()
    val allEpisodes by viewModel.allEpisodes.collectAsStateWithLifecycle()
    val activeEpisode by viewModel.activeEpisode.collectAsStateWithLifecycle()
    val activeEpisodeId by viewModel.activeEpisodeId.collectAsStateWithLifecycle()
    val audioStems by viewModel.audioStems.collectAsStateWithLifecycle()
    val transcripts by viewModel.transcripts.collectAsStateWithLifecycle()
    val dialoguesChronological by viewModel.dialoguesChronological.collectAsStateWithLifecycle()
    val dialoguesCharacterWise by viewModel.dialoguesCharacterWiseBatches.collectAsStateWithLifecycle()
    val speakers by viewModel.speakers.collectAsStateWithLifecycle()
    val dictionaries by viewModel.dictionaries.collectAsStateWithLifecycle()
    val voiceGenRecords by viewModel.voiceGenerationRecords.collectAsStateWithLifecycle()
    val timelineTracks by viewModel.timelineTracks.collectAsStateWithLifecycle()
    val qualityChecks by viewModel.qualityChecks.collectAsStateWithLifecycle()
    val models by viewModel.models.collectAsStateWithLifecycle()
    val agentJobs by viewModel.agentJobsForEpisode.collectAsStateWithLifecycle()
    val allHistory by viewModel.allAgentJobHistory.collectAsStateWithLifecycle()
    val exports by viewModel.exportsForEpisode.collectAsStateWithLifecycle()
    val pipelineBanner by viewModel.pipelineBanner.collectAsStateWithLifecycle()
    val playbackState by viewModel.playbackState.collectAsStateWithLifecycle()
    val selectedAgentNumber by viewModel.selectedAgentNumber.collectAsStateWithLifecycle()
    val selectedModelId by viewModel.selectedModelId.collectAsStateWithLifecycle()
    val selectedSpeakerLabel by viewModel.selectedSpeakerLabel.collectAsStateWithLifecycle()
    val selectedDialogueId by viewModel.selectedDialogueId.collectAsStateWithLifecycle()
    val timelineZoom by viewModel.timelineZoom.collectAsStateWithLifecycle()
    val canUndo by viewModel.canUndo.collectAsStateWithLifecycle()
    val canRedo by viewModel.canRedo.collectAsStateWithLifecycle()
    val workerHealth by viewModel.workerHealth.collectAsStateWithLifecycle()

    val coroutineScope = rememberCoroutineScope()
    var showAllScreensNavigatorSheet by remember { mutableStateOf(false) }

    // Mandatory BackHandler for custom screen state switching
    BackHandler(
        enabled = currentScreen != StudioScreen.DASHBOARD &&
            currentScreen != StudioScreen.SPLASH &&
            currentScreen != StudioScreen.ONBOARDING
    ) {
        viewModel.navigateBack()
    }

    if (currentScreen == StudioScreen.SPLASH) {
        SplashScreen(
            onboardingCompleted = studioConfig.onboardingCompleted,
            onSplashFinished = { done -> viewModel.finishSplash(done) }
        )
        return
    }

    if (currentScreen == StudioScreen.ONBOARDING) {
        OnboardingScreen(
            onFinishOnboarding = { viewModel.completeOnboarding() }
        )
        return
    }

    val primaryBottomTabs = listOf(
        Triple(StudioScreen.DASHBOARD, "Studio", Icons.Default.Dashboard),
        Triple(StudioScreen.PROCESSING_CENTER, "Pipeline", Icons.Default.GraphicEq),
        Triple(StudioScreen.SPEAKER_LIBRARY, "Voices & Dict", Icons.Default.RecordVoiceOver),
        Triple(StudioScreen.MULTITRACK_TIMELINE_EDITOR, "Timeline", Icons.Default.Audiotrack),
        Triple(StudioScreen.MODEL_MANAGER, "Models & QC", Icons.Default.CloudDownload)
    )

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        containerColor = StudioBackground,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = StudioSurface,
                    titleContentColor = StudioTextPrimary
                ),
                navigationIcon = {
                    if (currentScreen != StudioScreen.DASHBOARD) {
                        IconButton(
                            onClick = { viewModel.navigateBack() },
                            modifier = Modifier.testTag("top_back_btn")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = StudioTextPrimary
                            )
                        }
                    }
                },
                title = {
                    Column {
                        Text(
                            text = currentScreen.title,
                            style = MaterialTheme.typography.titleMedium,
                            color = StudioTextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${activeEpisode?.episodeTitle ?: "AnimeDub Studio"} • Stage ${activeEpisode?.currentStageNumber ?: 1}/14",
                            style = MaterialTheme.typography.labelSmall,
                            color = StudioCyan,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { showAllScreensNavigatorSheet = true },
                        modifier = Modifier.testTag("open_all_workstations_sheet_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Apps,
                            contentDescription = "All 28 Studio Workstations",
                            tint = StudioViolet
                        )
                    }
                    IconButton(
                        onClick = { viewModel.navigateTo(StudioScreen.SETTINGS) },
                        modifier = Modifier.testTag("top_settings_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Studio Settings",
                            tint = StudioTextSecondary
                        )
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = StudioSurface,
                tonalElevation = 8.dp
            ) {
                primaryBottomTabs.forEach { (tabScreen, tabLabel, tabIcon) ->
                    val isSelected = currentScreen == tabScreen ||
                        (tabScreen == StudioScreen.DASHBOARD && currentScreen.sectionGroup == "Studio") ||
                        (tabScreen == StudioScreen.PROCESSING_CENTER && currentScreen.sectionGroup == "Pipeline") ||
                        (tabScreen == StudioScreen.SPEAKER_LIBRARY && currentScreen.sectionGroup == "Voices") ||
                        (tabScreen == StudioScreen.MULTITRACK_TIMELINE_EDITOR && currentScreen.sectionGroup == "Timeline") ||
                        (tabScreen == StudioScreen.MODEL_MANAGER && currentScreen.sectionGroup == "Models & QC")

                    NavigationBarItem(
                        selected = isSelected,
                        onClick = { viewModel.navigateTo(tabScreen) },
                        icon = {
                            Icon(
                                imageVector = tabIcon,
                                contentDescription = tabLabel
                            )
                        },
                        label = {
                            Text(
                                text = tabLabel,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = StudioCyan,
                            selectedTextColor = StudioCyan,
                            indicatorColor = StudioVioletContainer,
                            unselectedIconColor = StudioTextSecondary,
                            unselectedTextColor = StudioTextSecondary
                        ),
                        modifier = Modifier.testTag("bottom_tab_${tabLabel.lowercase().replace(" ", "_")}")
                    )
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            PipelineLiveStatusBanner(
                bannerState = pipelineBanner,
                playbackState = playbackState,
                onStopAudio = { viewModel.audioPlayer.stop() },
                onDismissBanner = { viewModel.dismissBanner() }
            )

            when (currentScreen) {
                StudioScreen.DASHBOARD -> DashboardScreen(
                    projects = projects,
                    activeEpisode = activeEpisode,
                    dialogues = dialoguesChronological,
                    models = models,
                    recentJobs = agentJobs,
                    storageUsedBytes = viewModel.modelDownloadManager.getModelsStorageUsedBytes(),
                    storageFreeBytes = viewModel.modelDownloadManager.getAvailableStorageBytes(),
                    onNavigate = { viewModel.navigateTo(it) },
                    onRunFullPipeline = { viewModel.runPipelineStages(1, 14) }
                )

                StudioScreen.PROJECT_LIBRARY -> ProjectLibraryScreen(
                    projects = projects,
                    episodes = allEpisodes,
                    activeEpisodeId = activeEpisodeId,
                    onSelectEpisode = { epId, projId -> viewModel.selectActiveEpisode(epId, projId) },
                    onCreateNewProjectClick = { viewModel.navigateTo(StudioScreen.NEW_PROJECT) },
                    onOpenProcessingCenter = { viewModel.navigateTo(StudioScreen.PROCESSING_CENTER) }
                )

                StudioScreen.NEW_PROJECT -> NewProjectScreen(
                    onCreateProject = { title, series, sr, tts ->
                        viewModel.createNewProject(title, series, sr, tts)
                    }
                )

                StudioScreen.VIDEO_UPLOAD -> VideoUploadScreen(
                    activeEpisode = activeEpisode,
                    playbackState = playbackState,
                    onMediaUriSelected = { uri -> viewModel.handleUploadedMediaUri(uri) },
                    onPlayAudio = { path, lbl -> viewModel.audioPlayer.playFile(path, lbl) },
                    onStopAudio = { viewModel.audioPlayer.stop() },
                    onProceedToMediaInfo = { viewModel.navigateTo(StudioScreen.MEDIA_INFORMATION) },
                    onRunPipeline = {
                        viewModel.runPipelineStages(1, 14)
                        viewModel.navigateTo(StudioScreen.PROCESSING_CENTER)
                    }
                )

                StudioScreen.MEDIA_INFORMATION -> MediaInformationScreen(
                    activeEpisode = activeEpisode,
                    playbackState = playbackState,
                    onPlayAudio = { path, lbl -> viewModel.audioPlayer.playFile(path, lbl) },
                    onStopAudio = { viewModel.audioPlayer.stop() },
                    onOpenBackgroundSeparation = { viewModel.navigateTo(StudioScreen.BACKGROUND_AUDIO_STUDIO) }
                )

                StudioScreen.PROCESSING_CENTER -> ProcessingCenterScreen(
                    activeEpisode = activeEpisode,
                    agentJobs = agentJobs,
                    onRunFullPipeline = { viewModel.runPipelineStages(1, 14) },
                    onRunSingleStage = { stage -> viewModel.runPipelineStages(stage, stage) },
                    onInspectAgent = { agentNum -> viewModel.selectAgentForDetails(agentNum) }
                )

                StudioScreen.AGENT_PROCESSING_DETAILS -> AgentProcessingDetailsScreen(
                    selectedAgentNumber = selectedAgentNumber,
                    agentJobs = agentJobs,
                    onSelectAgentNumber = { viewModel.selectAgentForDetails(it) },
                    onRunStage = { stage -> viewModel.runPipelineStages(stage, stage) }
                )

                StudioScreen.JAPANESE_TRANSCRIPT_STUDIO -> JapaneseTranscriptStudioScreen(
                    transcripts = transcripts,
                    playbackState = playbackState,
                    onPlayAudio = { path, lbl -> viewModel.audioPlayer.playFile(path, lbl) },
                    onStopAudio = { viewModel.audioPlayer.stop() },
                    onReRunTranscription = { viewModel.runPipelineStages(4, 5) }
                )

                StudioScreen.DIALOGUE_SEGMENTATION_EDITOR -> DialogueSegmentationEditorScreen(
                    dialogues = dialoguesChronological,
                    playbackState = playbackState,
                    onPlayAudio = { path, lbl -> viewModel.audioPlayer.playFile(path, lbl) },
                    onStopAudio = { viewModel.audioPlayer.stop() },
                    onReRunSegmentation = { viewModel.runPipelineStages(5, 7) }
                )

                StudioScreen.SPEAKER_LIBRARY -> SpeakerLibraryScreen(
                    speakers = speakers,
                    dialogues = dialoguesChronological,
                    playbackState = playbackState,
                    onPlayAudio = { path, lbl -> viewModel.audioPlayer.playFile(path, lbl) },
                    onStopAudio = { viewModel.audioPlayer.stop() },
                    onRenameSpeaker = { sp, newName, consent ->
                        viewModel.renameSpeakerCharacter(sp, newName, consent)
                    },
                    onReassignDialogueSpeaker = { dlg, newSp ->
                        viewModel.reassignDialogueSpeaker(dlg, newSp)
                    },
                    onInspectVoiceAnalysis = { label -> viewModel.selectSpeakerForDetails(label) },
                    onOpenVoiceProfiles = { viewModel.navigateTo(StudioScreen.CHARACTER_VOICE_PROFILES) }
                )

                StudioScreen.CHARACTER_VOICE_PROFILES -> CharacterVoiceProfilesScreen(
                    speakers = speakers,
                    playbackState = playbackState,
                    onPlayAudio = { path, lbl -> viewModel.audioPlayer.playFile(path, lbl) },
                    onStopAudio = { viewModel.audioPlayer.stop() },
                    onUpdateSpeakerConsent = { sp, name, consent ->
                        viewModel.renameSpeakerCharacter(sp, name, consent)
                    }
                )

                StudioScreen.VOICE_ANALYSIS_DETAILS -> VoiceAnalysisDetailsScreen(
                    speakers = speakers,
                    selectedSpeakerLabel = selectedSpeakerLabel,
                    onSelectSpeaker = { viewModel.selectSpeakerForDetails(it) }
                )

                StudioScreen.TRANSLATION_STUDIO -> TranslationStudioScreen(
                    dialogues = dialoguesChronological,
                    onReRunGeminiTranslation = { viewModel.runPipelineStages(8, 8) },
                    onUpdateSingleLineAndRegenerate = { dlgId, hinglish, emotion ->
                        viewModel.updateAndRegenerateSingleDialogue(dlgId, hinglish, emotion)
                    },
                    onApplyScopedTermReplacement = { targetId, jpTerm, oldWord, newHinglish, pron, scope ->
                        viewModel.applyDictionaryScopedChange(targetId, jpTerm, oldWord, newHinglish, pron, scope)
                    },
                    onOpenGlobalDictionary = { viewModel.navigateTo(StudioScreen.GLOBAL_DICTIONARY) },
                    onOpenAnimeDictionary = { viewModel.navigateTo(StudioScreen.ANIME_DICTIONARY) }
                )

                StudioScreen.GLOBAL_DICTIONARY -> DictionaryManagerScreen(
                    levelFilter = "GLOBAL",
                    entries = dictionaries,
                    onSaveEntry = { viewModel.addOrUpdateDictionaryEntry(it) },
                    onDeleteEntry = { viewModel.deleteDictionaryEntry(it) },
                    onSwitchLevelView = { viewModel.navigateTo(StudioScreen.ANIME_DICTIONARY) }
                )

                StudioScreen.ANIME_DICTIONARY -> DictionaryManagerScreen(
                    levelFilter = "ANIME_PROJECT",
                    entries = dictionaries,
                    onSaveEntry = { viewModel.addOrUpdateDictionaryEntry(it) },
                    onDeleteEntry = { viewModel.deleteDictionaryEntry(it) },
                    onSwitchLevelView = { viewModel.navigateTo(StudioScreen.GLOBAL_DICTIONARY) }
                )

                StudioScreen.VOICE_GENERATION_STUDIO -> VoiceGenerationStudioScreen(
                    dialogues = dialoguesChronological,
                    speakers = speakers,
                    generationHistory = voiceGenRecords,
                    selectedTtsEngineId = studioConfig.selectedTtsEngine,
                    playbackState = playbackState,
                    onSelectTtsEngine = { engineId ->
                        coroutineScope.launch {
                            viewModel.prefsRepo.updateModelSelections(
                                studioConfig.selectedAsrVariant,
                                engineId,
                                studioConfig.selectedSeparationModel
                            )
                        }
                    },
                    onPlayAudio = { path, lbl -> viewModel.audioPlayer.playFile(path, lbl) },
                    onStopAudio = { viewModel.audioPlayer.stop() },
                    onRunAllCharacterBatches = { viewModel.runPipelineStages(9, 13) },
                    onRegenerateSingleDialogue = { id, text, emo ->
                        viewModel.updateAndRegenerateSingleDialogue(id, text, emo)
                    },
                    onOpenBatchQueue = { viewModel.navigateTo(StudioScreen.CHARACTER_WISE_GENERATION_QUEUE) }
                )

                StudioScreen.CHARACTER_WISE_GENERATION_QUEUE -> CharacterWiseGenerationQueueScreen(
                    dialoguesChronological = dialoguesChronological,
                    dialoguesCharacterWise = dialoguesCharacterWise,
                    playbackState = playbackState,
                    onPlayAudio = { path, lbl -> viewModel.audioPlayer.playFile(path, lbl) },
                    onStopAudio = { viewModel.audioPlayer.stop() },
                    onRunBatchGeneration = { viewModel.runPipelineStages(10, 12) },
                    onRetrySingleDialogue = { dlg ->
                        viewModel.updateAndRegenerateSingleDialogue(dlg.id, dlg.romanHinglishText, dlg.emotion)
                    },
                    onOpenChronologicalTimeline = { viewModel.navigateTo(StudioScreen.MULTITRACK_TIMELINE_EDITOR) }
                )

                StudioScreen.BACKGROUND_AUDIO_STUDIO -> BackgroundAudioStudioScreen(
                    stems = audioStems,
                    playbackState = playbackState,
                    onPlayAudio = { path, lbl -> viewModel.audioPlayer.playFile(path, lbl) },
                    onStopAudio = { viewModel.audioPlayer.stop() },
                    onReRunSeparation = { viewModel.runPipelineStages(3, 3) },
                    onOpenStemMixer = { viewModel.navigateTo(StudioScreen.AUDIO_STEM_MANAGER) }
                )

                StudioScreen.AUDIO_STEM_MANAGER -> AudioStemManagerScreen(
                    stems = audioStems,
                    playbackState = playbackState,
                    onPlayAudio = { path, lbl -> viewModel.audioPlayer.playFile(path, lbl) },
                    onStopAudio = { viewModel.audioPlayer.stop() },
                    onUpdateStemControls = { stem, gain, mute, solo ->
                        viewModel.updateStemControls(stem, gain, mute, solo)
                    }
                )

                StudioScreen.MULTITRACK_TIMELINE_EDITOR -> MultitrackTimelineEditorScreen(
                    activeEpisode = activeEpisode,
                    dialoguesChronological = dialoguesChronological,
                    tracks = timelineTracks,
                    stems = audioStems,
                    selectedDialogueId = selectedDialogueId,
                    zoomLevel = timelineZoom,
                    canUndo = canUndo,
                    canRedo = canRedo,
                    playbackState = playbackState,
                    onSelectDialogue = { viewModel.selectDialogue(it) },
                    onZoomChange = { viewModel.setTimelineZoom(it) },
                    onUndo = { viewModel.undoTimelineEdit() },
                    onRedo = { viewModel.redoTimelineEdit() },
                    onNudgeDialogueMs = { dlg, delta -> viewModel.adjustDialogueTimelinePosition(dlg, delta) },
                    onRestoreOriginalTimestamp = { dlg -> viewModel.restoreDialogueOriginalTimestamp(dlg) },
                    onUpdateTrackControls = { trk, gain, mute, solo ->
                        viewModel.updateTimelineTrackControls(trk, gain, mute, solo)
                    },
                    onPlayMasterMix = { path, lbl -> viewModel.audioPlayer.playFile(path, lbl) },
                    onStopAudio = { viewModel.audioPlayer.stop() },
                    onRegenerateSingleDialogue = { id, text, emo ->
                        viewModel.updateAndRegenerateSingleDialogue(id, text, emo)
                    }
                )

                StudioScreen.QUALITY_CONTROL_CENTER -> QualityControlCenterScreen(
                    checks = qualityChecks,
                    onReRunQcAudit = { viewModel.runPipelineStages(13, 13) },
                    onNavigateToRemediation = { viewModel.navigateTo(it) },
                    onOpenExportStudio = { viewModel.navigateTo(StudioScreen.FINAL_EXPORT_STUDIO) }
                )

                StudioScreen.FINAL_EXPORT_STUDIO -> FinalExportStudioScreen(
                    activeEpisode = activeEpisode,
                    exports = exports,
                    playbackState = playbackState,
                    onPlayAudio = { path, lbl -> viewModel.audioPlayer.playFile(path, lbl) },
                    onStopAudio = { viewModel.audioPlayer.stop() },
                    onExportEpisode = { fmt, res, vBr, aCodec, aBr, sr, name ->
                        viewModel.exportFinalDubbedEpisode(fmt, res, vBr, aCodec, aBr, sr, name)
                    }
                )

                StudioScreen.MODEL_MANAGER -> ModelManagerScreen(
                    models = models,
                    storageUsedBytes = viewModel.modelDownloadManager.getModelsStorageUsedBytes(),
                    storageFreeBytes = viewModel.modelDownloadManager.getAvailableStorageBytes(),
                    onInspectModel = { viewModel.selectModelForDetails(it) },
                    onDownloadModel = { viewModel.downloadModel(it) },
                    onDeleteModel = { viewModel.deleteModel(it) }
                )

                StudioScreen.MODEL_DOWNLOAD_DETAILS -> ModelDownloadDetailsScreen(
                    model = models.find { it.modelId == selectedModelId } ?: models.firstOrNull(),
                    onDownloadWithToken = { id, token -> viewModel.downloadModel(id, token) },
                    onDeleteModel = { viewModel.deleteModel(it) }
                )

                StudioScreen.PROJECT_PROCESSING_HISTORY -> ProjectProcessingHistoryScreen(
                    allHistory = allHistory
                )

                StudioScreen.SETTINGS -> SettingsScreen(
                    config = studioConfig,
                    workerHealth = workerHealth,
                    onSaveWorkerSettings = { en, url, tok, gpu, conc, to, lim ->
                        coroutineScope.launch {
                            viewModel.prefsRepo.updateWorkerSettings(en, url, tok, gpu, conc, to, lim)
                        }
                    },
                    onTestWorkerHealth = { url -> viewModel.verifyRemoteWorkerHealth(url) }
                )

                else -> DashboardScreen(
                    projects = projects,
                    activeEpisode = activeEpisode,
                    dialogues = dialoguesChronological,
                    models = models,
                    recentJobs = agentJobs,
                    storageUsedBytes = viewModel.modelDownloadManager.getModelsStorageUsedBytes(),
                    storageFreeBytes = viewModel.modelDownloadManager.getAvailableStorageBytes(),
                    onNavigate = { viewModel.navigateTo(it) },
                    onRunFullPipeline = { viewModel.runPipelineStages(1, 14) }
                )
            }
        }
    }

    // Modal Bottom Sheet for instant navigation to any of the 28 Studio Screens
    if (showAllScreensNavigatorSheet) {
        ModalBottomSheet(
            onDismissRequest = { showAllScreensNavigatorSheet = false },
            containerColor = StudioSurface
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Text(
                    text = "All 28 AnimeDub Studio Workstations",
                    style = MaterialTheme.typography.headlineSmall,
                    color = StudioTextPrimary,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(10.dp))
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.height(420.dp)
                ) {
                    items(StudioScreen.entries) { scr ->
                        val isCurrent = scr == currentScreen
                        Surface(
                            color = if (isCurrent) StudioVioletContainer else StudioSurfaceElevated,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, if (isCurrent) StudioViolet else StudioBorder),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showAllScreensNavigatorSheet = false
                                    viewModel.navigateTo(scr)
                                }
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "${scr.ordinal + 1}. ${scr.title}",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = StudioTextPrimary
                                )
                                Text(
                                    text = scr.sectionGroup,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = StudioCyan
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
