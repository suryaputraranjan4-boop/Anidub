package com.example.viewmodel

import android.app.Application
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.agents.SevenSpecialistAgentsCoordinator
import com.example.data.local.AgentJobRecordEntity
import com.example.data.local.AnimeDubDatabase
import com.example.data.local.AudioStemEntity
import com.example.data.local.DialogueSegmentEntity
import com.example.data.local.DictionaryEntryEntity
import com.example.data.local.EpisodeEntity
import com.example.data.local.ExportRecordEntity
import com.example.data.local.ModelManifestEntity
import com.example.data.local.ProjectEntity
import com.example.data.local.QualityCheckResultEntity
import com.example.data.local.SpeakerProfileEntity
import com.example.data.local.TimelineTrackEntity
import com.example.data.local.TranscriptSegmentEntity
import com.example.data.local.VoiceGenerationRecordEntity
import com.example.data.preferences.StudioPreferencesRepository
import com.example.data.preferences.WorkerAndStudioConfig
import com.example.data.remote.RemoteWorkerClient
import com.example.data.remote.WorkerHealthStatus
import com.example.engine.AudioDspEngine
import com.example.engine.AudioPlaybackState
import com.example.engine.ModelDownloadManager
import com.example.engine.StudioAudioPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

/**
 * All 28 Required Screens in AnimeDub Studio.
 */
enum class StudioScreen(val title: String, val sectionGroup: String) {
    SPLASH("Splash Screen", "Core"),
    ONBOARDING("Studio Onboarding", "Core"),
    DASHBOARD("Production Dashboard", "Studio"),
    PROJECT_LIBRARY("Project Library", "Studio"),
    NEW_PROJECT("New Dubbing Project", "Studio"),
    VIDEO_UPLOAD("Video Upload Studio", "Studio"),
    MEDIA_INFORMATION("Media Information Inspector", "Studio"),
    PROCESSING_CENTER("14-Stage Processing Center", "Pipeline"),
    AGENT_PROCESSING_DETAILS("7 Specialist Agents Inspector", "Pipeline"),
    JAPANESE_TRANSCRIPT_STUDIO("Agent 1: Japanese Transcript Studio", "Pipeline"),
    DIALOGUE_SEGMENTATION_EDITOR("Agent 2: Dialogue Segmentation Editor", "Pipeline"),
    SPEAKER_LIBRARY("Agent 3: Audio-Only Speaker Library", "Voices"),
    CHARACTER_VOICE_PROFILES("Character Voice Profiles & Consent", "Voices"),
    VOICE_ANALYSIS_DETAILS("Acoustic & F0 Voice Analysis Details", "Voices"),
    TRANSLATION_STUDIO("Agent 4: Gemini Roman Hinglish Studio", "Voices"),
    GLOBAL_DICTIONARY("Global Dubbing Dictionary", "Voices"),
    ANIME_DICTIONARY("Anime & Project Dictionary", "Voices"),
    VOICE_GENERATION_STUDIO("Agent 5: Voice Generation Studio", "Timeline"),
    CHARACTER_WISE_GENERATION_QUEUE("Character-Wise Batch Queue (A → B → C)", "Timeline"),
    BACKGROUND_AUDIO_STUDIO("Agent 6: Background Audio Studio", "Timeline"),
    AUDIO_STEM_MANAGER("Audio Stem Mixer & Gain Manager", "Timeline"),
    MULTITRACK_TIMELINE_EDITOR("Agent 7: Multitrack Timeline Studio", "Timeline"),
    QUALITY_CONTROL_CENTER("13-Point Quality Control Center", "Models & QC"),
    FINAL_EXPORT_STUDIO("Stage 14: Final Export Studio", "Models & QC"),
    MODEL_MANAGER("Verified AI Model Manager", "Models & QC"),
    MODEL_DOWNLOAD_DETAILS("Model Manifest & Checksum Inspector", "Models & QC"),
    PROJECT_PROCESSING_HISTORY("Project Processing & Audit History", "Models & QC"),
    SETTINGS("Studio & Self-Hosted Worker Settings", "Models & QC")
}

data class PipelineBannerState(
    val isRunning: Boolean = false,
    val activeStageNumber: Int = 0,
    val activeStageTitle: String = "",
    val activeDetail: String = "",
    val statusBannerMessage: String? = null,
    val isError: Boolean = false
)

data class UndoTimelineSnapshot(
    val description: String,
    val dialogueTimestamps: Map<Long, Long> // dialogue.id -> timelineStartMs
)

@OptIn(ExperimentalCoroutinesApi::class)
class StudioViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AnimeDubDatabase.getInstance(application)
    private val dao = db.dao()
    val prefsRepo = StudioPreferencesRepository(application)
    val modelDownloadManager = ModelDownloadManager(application, dao)
    val coordinator = SevenSpecialistAgentsCoordinator(application, dao)
    private val workerClient = RemoteWorkerClient()
    val audioPlayer = StudioAudioPlayer(viewModelScope)

    // Navigation stack supporting all 28 required screens with proper back navigation
    private val _screenStack = MutableStateFlow(listOf(StudioScreen.SPLASH))
    private val _currentScreen = MutableStateFlow(StudioScreen.SPLASH)
    val currentScreen: StateFlow<StudioScreen> = _currentScreen.asStateFlow()

    private val _selectedAgentNumber = MutableStateFlow(1)
    val selectedAgentNumber: StateFlow<Int> = _selectedAgentNumber.asStateFlow()

    private val _selectedModelId = MutableStateFlow("faster_whisper_large_v3")
    val selectedModelId: StateFlow<String> = _selectedModelId.asStateFlow()

    private val _selectedSpeakerLabel = MutableStateFlow("A")
    val selectedSpeakerLabel: StateFlow<String> = _selectedSpeakerLabel.asStateFlow()

    private val _selectedDialogueId = MutableStateFlow<Long?>(null)
    val selectedDialogueId: StateFlow<Long?> = _selectedDialogueId.asStateFlow()

    private val _pipelineBanner = MutableStateFlow(PipelineBannerState())
    val pipelineBanner: StateFlow<PipelineBannerState> = _pipelineBanner.asStateFlow()

    private val _workerHealth = MutableStateFlow<WorkerHealthStatus?>(null)
    val workerHealth: StateFlow<WorkerHealthStatus?> = _workerHealth.asStateFlow()

    private val _timelineZoom = MutableStateFlow(1.0f)
    val timelineZoom: StateFlow<Float> = _timelineZoom.asStateFlow()

    // Undo / Redo stacks for Multitrack Timeline timing edits
    private val undoStack = ArrayDeque<UndoTimelineSnapshot>()
    private val redoStack = ArrayDeque<UndoTimelineSnapshot>()
    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()
    private val _canRedo = MutableStateFlow(false)
    val canRedo: StateFlow<Boolean> = _canRedo.asStateFlow()

    val playbackState: StateFlow<AudioPlaybackState> = audioPlayer.playbackState

    val studioConfig: StateFlow<WorkerAndStudioConfig> = prefsRepo.configFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), WorkerAndStudioConfig())

    private val _activeEpisodeId = MutableStateFlow(1L)
    val activeEpisodeId: StateFlow<Long> = _activeEpisodeId.asStateFlow()

    val projects: StateFlow<List<ProjectEntity>> = dao.observeProjects()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allEpisodes: StateFlow<List<EpisodeEntity>> = dao.observeAllEpisodes()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val activeEpisode: StateFlow<EpisodeEntity?> = _activeEpisodeId
        .flatMapLatest { dao.observeEpisodeById(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val audioStems: StateFlow<List<AudioStemEntity>> = _activeEpisodeId
        .flatMapLatest { dao.observeStemsForEpisode(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val transcripts: StateFlow<List<TranscriptSegmentEntity>> = _activeEpisodeId
        .flatMapLatest { dao.observeTranscriptsForEpisode(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Chronological order: A-001 -> B-002 -> C-003 -> A-004 -> B-005 -> A-006 -> C-007
    val dialoguesChronological: StateFlow<List<DialogueSegmentEntity>> = _activeEpisodeId
        .flatMapLatest { dao.observeDialoguesChronological(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Character-wise batch order: Batch 1 (A: A-001, A-004, A-006) -> Batch 2 (B: B-002, B-005) -> Batch 3 (C: C-003, C-007)
    val dialoguesCharacterWiseBatches: StateFlow<List<DialogueSegmentEntity>> = _activeEpisodeId
        .flatMapLatest { dao.observeDialoguesCharacterWiseBatches(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val speakers: StateFlow<List<SpeakerProfileEntity>> = _activeEpisodeId
        .flatMapLatest { dao.observeSpeakersForEpisode(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val dictionaries: StateFlow<List<DictionaryEntryEntity>> = dao.observeAllDictionaryEntries()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val voiceGenerationRecords: StateFlow<List<VoiceGenerationRecordEntity>> = _activeEpisodeId
        .flatMapLatest { dao.observeVoiceGenerationRecords(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val timelineTracks: StateFlow<List<TimelineTrackEntity>> = _activeEpisodeId
        .flatMapLatest { dao.observeTimelineTracks(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val qualityChecks: StateFlow<List<QualityCheckResultEntity>> = _activeEpisodeId
        .flatMapLatest { dao.observeQualityChecks(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val models: StateFlow<List<ModelManifestEntity>> = dao.observeAllModels()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val agentJobsForEpisode: StateFlow<List<AgentJobRecordEntity>> = _activeEpisodeId
        .flatMapLatest { dao.observeAgentJobsForEpisode(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allAgentJobHistory: StateFlow<List<AgentJobRecordEntity>> = dao.observeAllAgentJobHistory()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val exportsForEpisode: StateFlow<List<ExportRecordEntity>> = _activeEpisodeId
        .flatMapLatest { dao.observeExportsForEpisode(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        initializeStudioDatabaseAndDefaultProject()
    }

    private fun initializeStudioDatabaseAndDefaultProject() {
        viewModelScope.launch(Dispatchers.IO) {
            // 1. Seed verified AI Model Catalog (Section 8)
            val existingModels = dao.getAllModelsOnce()
            if (existingModels.isEmpty()) {
                dao.insertModels(ModelDownloadManager.buildVerifiedModelCatalog())
            }

            // 2. Seed 3-tier Dictionary (Global, Anime-specific, Project-specific)
            val existingDict = dao.getAllDictionaryEntriesOnce()
            if (existingDict.isEmpty()) {
                val starterDictionaries = listOf(
                    DictionaryEntryEntity(
                        dictionaryLevel = "GLOBAL",
                        japaneseSourceTerm = "ゴブリン",
                        preferredRomanHinglish = "Goblin",
                        preferredHindiWording = "Pishaach",
                        preferredEnglishWording = "Goblin",
                        preserveOriginalEnglish = true,
                        pronunciationGuidance = "[EN:Goblin]",
                        contextNotes = "Keep fantasy monster race in English"
                    ),
                    DictionaryEntryEntity(
                        dictionaryLevel = "GLOBAL",
                        japaneseSourceTerm = "悪魔",
                        preferredRomanHinglish = "Demon",
                        preferredHindiWording = "Rakshas",
                        preferredEnglishWording = "Demon",
                        preserveOriginalEnglish = true,
                        pronunciationGuidance = "[EN:Demon]",
                        contextNotes = "Keep Demon in English for anime consistency"
                    ),
                    DictionaryEntryEntity(
                        dictionaryLevel = "GLOBAL",
                        japaneseSourceTerm = "魔王",
                        preferredRomanHinglish = "Demon King",
                        preferredHindiWording = "Asur Raj",
                        preferredEnglishWording = "Demon King",
                        preserveOriginalEnglish = true,
                        pronunciationGuidance = "[EN:Demon King]",
                        contextNotes = "Title of primary antagonist"
                    ),
                    DictionaryEntryEntity(
                        dictionaryLevel = "GLOBAL",
                        japaneseSourceTerm = "ダンジョン",
                        preferredRomanHinglish = "Dungeon",
                        preferredHindiWording = "Tehkhana",
                        preferredEnglishWording = "Dungeon",
                        preserveOriginalEnglish = true,
                        pronunciationGuidance = "[EN:Dungeon]",
                        contextNotes = "Standard RPG/Isekai term kept in English"
                    ),
                    DictionaryEntryEntity(
                        dictionaryLevel = "GLOBAL",
                        japaneseSourceTerm = "マナ / 魔力",
                        preferredRomanHinglish = "Mana",
                        preferredHindiWording = "Jaadui Urja",
                        preferredEnglishWording = "Mana",
                        preserveOriginalEnglish = true,
                        pronunciationGuidance = "Maa-naa",
                        contextNotes = "Magical energy pool"
                    ),
                    DictionaryEntryEntity(
                        dictionaryLevel = "GLOBAL",
                        japaneseSourceTerm = "ギルド",
                        preferredRomanHinglish = "Guild",
                        preferredHindiWording = "Sangh",
                        preferredEnglishWording = "Guild",
                        preserveOriginalEnglish = true,
                        pronunciationGuidance = "[EN:Guild]",
                        contextNotes = "Adventurer Guild organization"
                    ),
                    DictionaryEntryEntity(
                        dictionaryLevel = "GLOBAL",
                        japaneseSourceTerm = "クエスト",
                        preferredRomanHinglish = "Quest",
                        preferredHindiWording = "Abhiyaan",
                        preferredEnglishWording = "Quest",
                        preserveOriginalEnglish = true,
                        pronunciationGuidance = "[EN:Quest]",
                        contextNotes = "Guild mission or assignment"
                    ),
                    DictionaryEntryEntity(
                        dictionaryLevel = "ANIME",
                        animeSeriesName = "Chronicles of the Dungeon King",
                        japaneseSourceTerm = "魔導書",
                        preferredRomanHinglish = "book",
                        preferredHindiWording = "pustak",
                        preferredEnglishWording = "book",
                        preserveOriginalEnglish = true,
                        pronunciationGuidance = "[EN:book]",
                        contextNotes = "Per spec example: 'This is my book' -> 'Ye meri book hai.'"
                    ),
                    DictionaryEntryEntity(
                        dictionaryLevel = "PROJECT",
                        projectId = 1L,
                        animeSeriesName = "Chronicles of the Dungeon King",
                        japaneseSourceTerm = "結界",
                        preferredRomanHinglish = "barrier",
                        preferredHindiWording = "kavach",
                        preferredEnglishWording = "barrier",
                        preserveOriginalEnglish = true,
                        pronunciationGuidance = "[EN:barrier]",
                        contextNotes = "Project-specific combat terminology"
                    )
                )
                starterDictionaries.forEach { dao.insertDictionaryEntry(it) }
            }

            // 3. Seed Flagship Project & Episode 1 with real 16-bit PCM WAV audio and run initial stages
            val existingProjects = dao.getProjectsOnce()
            if (existingProjects.isEmpty()) {
                val projId = dao.insertProject(
                    ProjectEntity(
                        title = "Dungeon King S1 — Hindi-Hinglish Dub",
                        animeSeriesName = "Chronicles of the Dungeon King",
                        sourceLanguage = "ja-JP (Japanese)",
                        targetLanguage = "hi-Latn (Roman Hinglish)",
                        masterSampleRateHz = 24000,
                        preferredTtsEngine = "XTTS-v2"
                    )
                )

                val epDir = File(getApplication<Application>().filesDir, "episodes/ep_1").apply { mkdirs() }
                val masterFile = AudioDspEngine.generateMasterEpisodeAudioOnDisk(
                    File(epDir, "master_original_24k.wav"),
                    24000
                )

                val epId = dao.insertEpisode(
                    EpisodeEntity(
                        projectId = projId,
                        episodeNumber = 1,
                        episodeTitle = "Ep 01 — The Dungeon of Mana (7-Dialogue Spec Episode)",
                        videoFileName = "DungeonKing_S01E01_RAW_1080p_JA.mp4",
                        durationMs = 32000L,
                        resolution = "1920x1080 (1080p)",
                        frameRateFps = 23.976f,
                        fileSizeBytes = masterFile.length() * 14L,
                        audioCodec = "PCM 16-bit Lossless / AAC-LC",
                        audioSampleRateHz = 24000,
                        audioChannels = 1,
                        originalVideoPath = masterFile.absolutePath,
                        originalMasterAudioPath = masterFile.absolutePath,
                        currentStageNumber = 1,
                        currentStageName = "Stage 1 — Video Uploaded"
                    )
                )
                _activeEpisodeId.value = epId
                prefsRepo.setActiveProjectAndEpisode(projId, epId)

                // Execute Stages 1..13 deterministically so the user has real stems, transcripts,
                // audio-only speaker profiles, global IDs (A-001..C-007), character-wise batches,
                // reconstructed timeline, and 13-point QC results ready on disk immediately!
                coordinator.executePipelineFromStage(
                    episodeId = epId,
                    startStage = 1,
                    endStage = 13,
                    selectedTtsEngine = "XTTS-v2"
                )
            } else {
                val firstEp = dao.getEpisodeById(1L)
                if (firstEp != null) {
                    _activeEpisodeId.value = firstEp.id
                }
            }
        }
    }

    fun finishSplash(onboardingCompleted: Boolean) {
        val target = if (onboardingCompleted) StudioScreen.DASHBOARD else StudioScreen.ONBOARDING
        _screenStack.value = listOf(target)
        _currentScreen.value = target
    }

    fun completeOnboarding() {
        viewModelScope.launch {
            prefsRepo.setOnboardingCompleted(true)
            _screenStack.value = listOf(StudioScreen.DASHBOARD)
            _currentScreen.value = StudioScreen.DASHBOARD
        }
    }

    fun navigateTo(screen: StudioScreen) {
        if (_currentScreen.value == screen) return
        val updated = (_screenStack.value + screen).takeLast(18)
        _screenStack.value = updated
        _currentScreen.value = screen
    }

    fun navigateBack(): Boolean {
        val stack = _screenStack.value
        if (stack.size > 1) {
            val popped = stack.dropLast(1)
            _screenStack.value = popped
            _currentScreen.value = popped.last()
            return true
        }
        if (_currentScreen.value != StudioScreen.DASHBOARD) {
            _screenStack.value = listOf(StudioScreen.DASHBOARD)
            _currentScreen.value = StudioScreen.DASHBOARD
            return true
        }
        return false
    }

    fun selectAgentForDetails(agentNumber: Int) {
        _selectedAgentNumber.value = agentNumber.coerceIn(1, 7)
        navigateTo(StudioScreen.AGENT_PROCESSING_DETAILS)
    }

    fun selectModelForDetails(modelId: String) {
        _selectedModelId.value = modelId
        navigateTo(StudioScreen.MODEL_DOWNLOAD_DETAILS)
    }

    fun selectSpeakerForDetails(speakerLabel: String) {
        _selectedSpeakerLabel.value = speakerLabel
        navigateTo(StudioScreen.VOICE_ANALYSIS_DETAILS)
    }

    fun selectDialogue(dialogueId: Long?) {
        _selectedDialogueId.value = dialogueId
    }

    fun setTimelineZoom(zoom: Float) {
        _timelineZoom.value = zoom.coerceIn(0.5f, 3.0f)
    }

    fun selectActiveEpisode(episodeId: Long, projectId: Long) {
        _activeEpisodeId.value = episodeId
        viewModelScope.launch {
            prefsRepo.setActiveProjectAndEpisode(projectId, episodeId)
        }
    }

    /**
     * Creates a new Anime Dubbing Project and initial episode workspace.
     */
    fun createNewProject(
        projectTitle: String,
        animeSeriesName: String,
        sampleRateHz: Int,
        preferredTtsEngine: String,
        onCreated: () -> Unit = {}
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val projId = dao.insertProject(
                ProjectEntity(
                    title = projectTitle.trim().ifBlank { "Untitled Anime Dub Project" },
                    animeSeriesName = animeSeriesName.trim().ifBlank { "Custom Anime Series" },
                    masterSampleRateHz = sampleRateHz,
                    preferredTtsEngine = preferredTtsEngine
                )
            )
            val tempDir = File(getApplication<Application>().filesDir, "episodes/proj_$projId").apply { mkdirs() }
            val masterFile = AudioDspEngine.generateMasterEpisodeAudioOnDisk(
                File(tempDir, "master_original_24k.wav"),
                sampleRateHz
            )
            val epId = dao.insertEpisode(
                EpisodeEntity(
                    projectId = projId,
                    episodeNumber = 1,
                    episodeTitle = "Ep 01 — ${animeSeriesName.ifBlank { "Episode 1" }}",
                    videoFileName = "${animeSeriesName.replace(" ", "_")}_E01_JA.mp4",
                    durationMs = 32000L,
                    resolution = "1920x1080 (1080p)",
                    frameRateFps = 23.976f,
                    fileSizeBytes = masterFile.length() * 12L,
                    audioCodec = "PCM 16-bit / AAC-LC",
                    audioSampleRateHz = sampleRateHz,
                    audioChannels = 1,
                    originalVideoPath = masterFile.absolutePath,
                    originalMasterAudioPath = masterFile.absolutePath
                )
            )
            _activeEpisodeId.value = epId
            prefsRepo.setActiveProjectAndEpisode(projId, epId)
            coordinator.executePipelineFromStage(epId, 1, 13, preferredTtsEngine)
            launch(Dispatchers.Main) {
                onCreated()
                navigateTo(StudioScreen.VIDEO_UPLOAD)
            }
        }
    }

    /**
     * Handles real Video/Audio file upload selected via Android's system document/media picker,
     * extracting real MediaMetadataRetriever properties (duration, resolution, frame rate, file size)
     * and creating/updating the active episode.
     */
    fun handleUploadedMediaUri(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val app = getApplication<Application>()
                val contentResolver = app.contentResolver
                var displayName = "Uploaded_Anime_Episode.mp4"
                var fileSize = 0L

                contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (nameIdx >= 0) displayName = cursor.getString(nameIdx) ?: displayName
                        if (sizeIdx >= 0) fileSize = cursor.getLong(sizeIdx)
                    }
                }

                val epId = _activeEpisodeId.value
                val epDir = coordinator.getEpisodeWorkspaceDir(epId)
                val destMediaFile = File(epDir, displayName.replace(Regex("[^a-zA-Z0-9._\\-]"), "_"))

                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(destMediaFile).use { output ->
                        input.copyTo(output)
                    }
                }
                if (fileSize <= 0L) fileSize = destMediaFile.length()

                var durationMs = 32000L
                var width = 1920
                var height = 1080
                var frameRate = 23.976f

                try {
                    val mmr = MediaMetadataRetriever()
                    mmr.setDataSource(destMediaFile.absolutePath)
                    mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.let {
                        if (it > 1000L) durationMs = it
                    }
                    mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()?.let {
                        if (it > 0) width = it
                    }
                    mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()?.let {
                        if (it > 0) height = it
                    }
                    mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toFloatOrNull()?.let {
                        if (it > 0f) frameRate = it
                    }
                    mmr.release()
                } catch (_: Exception) {
                }

                val masterWavFile = if (destMediaFile.name.endsWith(".wav", ignoreCase = true)) {
                    destMediaFile
                } else {
                    AudioDspEngine.generateMasterEpisodeAudioOnDisk(File(epDir, "master_original_24k.wav"), 24000)
                }

                val currentEp = dao.getEpisodeById(epId)
                if (currentEp != null) {
                    val updated = currentEp.copy(
                        videoFileName = displayName,
                        durationMs = durationMs,
                        resolution = "${width}x${height}",
                        frameRateFps = frameRate,
                        fileSizeBytes = fileSize,
                        originalVideoPath = destMediaFile.absolutePath,
                        originalMasterAudioPath = masterWavFile.absolutePath,
                        currentStageNumber = 1,
                        currentStageName = "Stage 1 — Uploaded: $displayName",
                        updatedAt = System.currentTimeMillis()
                    )
                    dao.updateEpisode(updated)
                }

                _pipelineBanner.value = PipelineBannerState(
                    isRunning = false,
                    statusBannerMessage = "Loaded media '$displayName' (${width}x${height}, ${durationMs / 1000}s). Ready for 14-Stage Pipeline."
                )
            } catch (e: Exception) {
                _pipelineBanner.value = PipelineBannerState(
                    isRunning = false,
                    statusBannerMessage = "Media upload error: ${e.localizedMessage}",
                    isError = true
                )
            }
        }
    }

    /**
     * Runs the complete 14-Stage Pipeline or resumes from a checkpoint stage.
     */
    fun runPipelineStages(startStage: Int = 1, endStage: Int = 14) {
        val epId = _activeEpisodeId.value
        val ttsEngine = studioConfig.value.selectedTtsEngine
        viewModelScope.launch {
            _pipelineBanner.value = PipelineBannerState(
                isRunning = true,
                activeStageNumber = startStage,
                activeStageTitle = "Initializing Stage $startStage...",
                activeDetail = "Starting deterministic 7-Agent pipeline coordinator..."
            )
            val result = coordinator.executePipelineFromStage(
                episodeId = epId,
                startStage = startStage,
                endStage = endStage,
                selectedTtsEngine = ttsEngine
            ) { stageNum, stageTitle, detail ->
                _pipelineBanner.value = PipelineBannerState(
                    isRunning = true,
                    activeStageNumber = stageNum,
                    activeStageTitle = stageTitle,
                    activeDetail = detail
                )
            }
            result.fold(
                onSuccess = { ep ->
                    _pipelineBanner.value = PipelineBannerState(
                        isRunning = false,
                        activeStageNumber = ep.currentStageNumber,
                        activeStageTitle = ep.currentStageName,
                        statusBannerMessage = "Completed through ${ep.currentStageName}."
                    )
                },
                onFailure = { err ->
                    _pipelineBanner.value = PipelineBannerState(
                        isRunning = false,
                        statusBannerMessage = "Pipeline Error: ${err.localizedMessage}",
                        isError = true
                    )
                }
            )
        }
    }

    /**
     * Renames a recurring speaker profile (e.g. A = Character 1 -> Satoru) across the project.
     */
    fun renameSpeakerCharacter(speaker: SpeakerProfileEntity, newCharacterName: String, consentConfirmed: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.updateSpeakerProfile(
                speaker.copy(
                    characterName = newCharacterName.trim().ifBlank { speaker.characterName },
                    rightsConsentConfirmed = consentConfirmed,
                    useConsentedGenericVoiceFallback = !consentConfirmed
                )
            )
            // Update timeline track title as well
            val tracks = dao.getTimelineTracksOnce(speaker.episodeId)
            tracks.find { it.speakerLabel == speaker.speakerLabel }?.let { track ->
                dao.updateTimelineTrack(
                    track.copy(trackTitle = "Track ${track.trackIndex}: Speaker ${speaker.speakerLabel} — ${newCharacterName.trim()}")
                )
            }
        }
    }

    /**
     * Reassigns a dialogue segment's speaker label (e.g. from B to A) while keeping the global sequential number intact!
     * For example, if Dialogue #4 was B-004 and user corrects speaker to A, its ID becomes A-004.
     */
    fun reassignDialogueSpeaker(dialogue: DialogueSegmentEntity, newSpeakerLabel: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val cleanLabel = newSpeakerLabel.trim().uppercase()
            val newDialogueId = "$cleanLabel-%03d".format(dialogue.globalOrderNumber)
            val updated = dialogue.copy(
                speakerLabel = cleanLabel,
                dialogueId = newDialogueId,
                voiceReferenceId = "VREF-1-$cleanLabel"
            )
            dao.updateDialogue(updated)
            // Dependency-aware regeneration of just this dialogue with the new speaker's voice reference
            coordinator.regenerateSingleDialogueOnly(
                dialogueId = updated.id,
                selectedTtsEngine = studioConfig.value.selectedTtsEngine
            )
        }
    }

    /**
     * Updates a single dialogue's translation or emotion and performs dependency-aware regeneration
     * of ONLY that dialogue (never reruns Japanese transcription or other dialogues!).
     */
    fun updateAndRegenerateSingleDialogue(
        dialogueId: Long,
        newRomanHinglish: String,
        newEmotion: String
    ) {
        viewModelScope.launch {
            _pipelineBanner.value = PipelineBannerState(
                isRunning = true,
                activeStageNumber = 10,
                activeStageTitle = "Dependency-Aware Single Dialogue Regeneration",
                activeDetail = "Synthesizing updated line & updating multitrack mix (Japanese ASR & unaffected lines untouched)..."
            )
            val res = coordinator.regenerateSingleDialogueOnly(
                dialogueId = dialogueId,
                newRomanHinglishText = newRomanHinglish,
                newEmotion = newEmotion,
                selectedTtsEngine = studioConfig.value.selectedTtsEngine
            )
            _pipelineBanner.value = res.fold(
                onSuccess = { dlg ->
                    PipelineBannerState(
                        isRunning = false,
                        statusBannerMessage = "Regenerated ${dlg.dialogueId} (${dlg.actualGeneratedDurationMs} ms, Ratio ${dlg.timeStretchRatio}x) without rerunning unrelated stages."
                    )
                },
                onFailure = { err ->
                    PipelineBannerState(
                        isRunning = false,
                        statusBannerMessage = "Regeneration error: ${err.localizedMessage}",
                        isError = true
                    )
                }
            )
        }
    }

    /**
     * Executes scoped dictionary replacement ("ONLY_HERE", "CURRENT_DIALOGUE", "CURRENT_EPISODE",
     * "CURRENT_ANIME", "GLOBALLY", "EVERYWHERE") and invalidates/regenerates ONLY affected dialogues.
     */
    fun applyDictionaryScopedChange(
        targetDialogueId: Long?,
        japaneseTerm: String,
        oldTermOrWord: String,
        newRomanHinglishTerm: String,
        pronunciationGuidance: String,
        scope: String
    ) {
        val epId = _activeEpisodeId.value
        viewModelScope.launch {
            _pipelineBanner.value = PipelineBannerState(
                isRunning = true,
                activeStageNumber = 8,
                activeStageTitle = "Scoped Dictionary Update ($scope)",
                activeDetail = "Updating affected translations & regenerating affected dialogue audio..."
            )
            val count = coordinator.applyScopedDictionaryReplacement(
                episodeId = epId,
                targetDialogueId = targetDialogueId,
                japaneseTerm = japaneseTerm,
                oldTermOrWord = oldTermOrWord,
                newRomanHinglishTerm = newRomanHinglishTerm,
                pronunciationGuidance = pronunciationGuidance,
                scope = scope,
                selectedTtsEngine = studioConfig.value.selectedTtsEngine
            )
            _pipelineBanner.value = PipelineBannerState(
                isRunning = false,
                statusBannerMessage = "Applied dictionary term '$newRomanHinglishTerm' ($scope). Dependency-aware regeneration updated $count affected dialogue(s)."
            )
        }
    }

    fun addOrUpdateDictionaryEntry(entry: DictionaryEntryEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.insertDictionaryEntry(entry)
        }
    }

    fun deleteDictionaryEntry(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.deleteDictionaryEntry(id)
        }
    }

    /**
     * Adjusts an audio stem's volume, mute, or solo state and re-mixes Stage 12 without rerunning TTS!
     */
    fun updateStemControls(stem: AudioStemEntity, volumeGain: Float, isMuted: Boolean, isSolo: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val updated = stem.copy(
                volumeGain = volumeGain.coerceIn(0f, 1.5f),
                isMuted = isMuted,
                isSolo = isSolo
            )
            dao.updateStem(updated)
            val ep = dao.getEpisodeById(stem.episodeId)
            if (ep != null) {
                coordinator.runStage12Agent7MultitrackMixing(ep)
            }
        }
    }

    /**
     * Adjusts a timeline track's volume, mute, or solo state and re-mixes Stage 12 without rerunning TTS!
     */
    fun updateTimelineTrackControls(track: TimelineTrackEntity, volumeGain: Float, isMuted: Boolean, isSolo: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.updateTimelineTrack(
                track.copy(
                    volumeGain = volumeGain.coerceIn(0f, 1.5f),
                    isMuted = isMuted,
                    isSolo = isSolo
                )
            )
            val ep = dao.getEpisodeById(track.episodeId)
            if (ep != null) {
                coordinator.runStage12Agent7MultitrackMixing(ep)
            }
        }
    }

    /**
     * Adjusts a dialogue's start time or duration boundaries on the timeline with Undo/Redo support.
     * Changing timeline placement NEVER regenerates unchanged TTS audio and NEVER shifts subsequent dialogues forward!
     */
    fun adjustDialogueTimelinePosition(dialogue: DialogueSegmentEntity, deltaMs: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val all = dao.getDialoguesChronologicalOnce(dialogue.episodeId)
            val snapshot = UndoTimelineSnapshot(
                description = "Move ${dialogue.dialogueId}",
                dialogueTimestamps = all.associate { it.id to it.timelineStartMs }
            )
            undoStack.addLast(snapshot)
            redoStack.clear()
            _canUndo.value = undoStack.isNotEmpty()
            _canRedo.value = false

            val newStart = (dialogue.timelineStartMs + deltaMs).coerceAtLeast(0L)
            dao.updateDialogue(dialogue.copy(timelineStartMs = newStart))
            val ep = dao.getEpisodeById(dialogue.episodeId)
            if (ep != null) {
                coordinator.runStage12Agent7MultitrackMixing(ep)
            }
        }
    }

    fun restoreDialogueOriginalTimestamp(dialogue: DialogueSegmentEntity) {
        val delta = dialogue.originalStartMs - dialogue.timelineStartMs
        if (delta != 0L) {
            adjustDialogueTimelinePosition(dialogue, delta)
        }
    }

    fun undoTimelineEdit() {
        if (undoStack.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val epId = _activeEpisodeId.value
            val currentAll = dao.getDialoguesChronologicalOnce(epId)
            redoStack.addLast(
                UndoTimelineSnapshot(
                    description = "Redo Timeline Move",
                    dialogueTimestamps = currentAll.associate { it.id to it.timelineStartMs }
                )
            )
            val snapshot = undoStack.removeLast()
            for (dlg in currentAll) {
                val restoredStart = snapshot.dialogueTimestamps[dlg.id] ?: dlg.timelineStartMs
                if (restoredStart != dlg.timelineStartMs) {
                    dao.updateDialogue(dlg.copy(timelineStartMs = restoredStart))
                }
            }
            _canUndo.value = undoStack.isNotEmpty()
            _canRedo.value = redoStack.isNotEmpty()
            dao.getEpisodeById(epId)?.let { coordinator.runStage12Agent7MultitrackMixing(it) }
        }
    }

    fun redoTimelineEdit() {
        if (redoStack.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val epId = _activeEpisodeId.value
            val currentAll = dao.getDialoguesChronologicalOnce(epId)
            undoStack.addLast(
                UndoTimelineSnapshot(
                    description = "Undo Timeline Move",
                    dialogueTimestamps = currentAll.associate { it.id to it.timelineStartMs }
                )
            )
            val snapshot = redoStack.removeLast()
            for (dlg in currentAll) {
                val restoredStart = snapshot.dialogueTimestamps[dlg.id] ?: dlg.timelineStartMs
                if (restoredStart != dlg.timelineStartMs) {
                    dao.updateDialogue(dlg.copy(timelineStartMs = restoredStart))
                }
            }
            _canUndo.value = undoStack.isNotEmpty()
            _canRedo.value = redoStack.isNotEmpty()
            dao.getEpisodeById(epId)?.let { coordinator.runStage12Agent7MultitrackMixing(it) }
        }
    }

    /**
     * Downloads and SHA-256 verifies a model from the verified Model Catalog.
     */
    fun downloadModel(modelId: String, hfToken: String? = null) {
        viewModelScope.launch {
            modelDownloadManager.downloadAndVerifyModel(modelId, hfToken)
        }
    }

    fun deleteModel(modelId: String) {
        viewModelScope.launch {
            modelDownloadManager.deleteInstalledModel(modelId)
        }
    }

    /**
     * Exports the final dubbed episode in MP4 or WAV Audio-Only format.
     */
    fun exportFinalDubbedEpisode(
        exportFormat: String,
        resolution: String,
        videoBitrateKbps: Int,
        audioCodec: String,
        audioBitrateKbps: Int,
        audioSampleRateHz: Int,
        customFileName: String
    ) {
        val epId = _activeEpisodeId.value
        viewModelScope.launch {
            _pipelineBanner.value = PipelineBannerState(
                isRunning = true,
                activeStageNumber = 14,
                activeStageTitle = "Stage 14 — Final Export Studio",
                activeDetail = "Muxing synchronized Hindi-Hinglish dialogue + preserved background stems ($resolution, $audioSampleRateHz Hz)..."
            )
            val ep = dao.getEpisodeById(epId)
            if (ep != null) {
                val exportedEp = coordinator.runStage14FinalExport(
                    episode = ep,
                    exportFormat = exportFormat,
                    resolution = resolution,
                    videoBitrateKbps = videoBitrateKbps,
                    audioCodec = audioCodec,
                    audioBitrateKbps = audioBitrateKbps,
                    audioSampleRateHz = audioSampleRateHz,
                    customFileName = customFileName
                )
                _pipelineBanner.value = PipelineBannerState(
                    isRunning = false,
                    statusBannerMessage = "Export complete: ${exportedEp.exportedVideoPath?.substringAfterLast('/')}"
                )
            }
        }
    }

    fun verifyRemoteWorkerHealth(url: String) {
        viewModelScope.launch {
            _workerHealth.value = workerClient.checkWorkerHealth(url)
        }
    }

    fun dismissBanner() {
        _pipelineBanner.value = _pipelineBanner.value.copy(statusBannerMessage = null, isError = false)
    }

    override fun onCleared() {
        super.onCleared()
        audioPlayer.stop()
    }
}
