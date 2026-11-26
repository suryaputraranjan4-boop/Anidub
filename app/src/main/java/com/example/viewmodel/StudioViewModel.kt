package com.example.viewmodel

import android.app.Application
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.agents.SevenSpecialistAgentsCoordinator
import com.example.agents.StageStatus
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
import com.example.data.preferences.GeminiApiKeyState
import com.example.data.preferences.GeminiApiKeyStore
import com.example.data.preferences.StudioConfig
import com.example.data.preferences.StudioPreferencesRepository
import com.example.data.remote.DirectGeminiApiClient
import com.example.data.remote.GeminiApiException
import com.example.data.remote.GeminiConnectionState
import com.example.data.remote.GeminiConnectionTestResult
import com.example.data.remote.GeminiDialogueRewriteResult
import com.example.engine.AudioDspEngine
import com.example.engine.AudioPlaybackState
import com.example.engine.HinglishTtsNormalizer
import com.example.engine.MediaExtractionResult
import com.example.engine.MixedScriptLanguageEngine
import com.example.engine.ModelDownloadManager
import com.example.engine.StudioAudioPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

enum class MainTab(val label: String) {
    HOME("Home"),
    PROJECTS("Projects"),
    PRODUCTION("Production"),
    TIMELINE("Timeline"),
    MODELS("Models")
}

enum class ProductionSection(val label: String) {
    PIPELINE("Pipeline"),
    TRANSCRIPT("Transcript"),
    SPEAKERS("Speakers"),
    TRANSLATION("Translate"),
    VOICE_GEN("Voice Gen"),
    AUDIO_STEMS("Stems"),
    QC_EXPORT("QC & Export")
}

enum class StudioOverlay {
    NONE,
    GUIDED_IMPORT,
    MODEL_DETAIL,
    PROCESSING_HISTORY,
    SETTINGS
}

enum class ProjectSortOrder(val label: String) {
    RECENT("Recently Updated"),
    TITLE_ASC("Title A–Z"),
    SERIES("Series Name")
}

enum class MediaInspectionStatus {
    IDLE,
    LOADING,
    READY,
    UNSUPPORTED,
    MISSING_AUDIO,
    EXTRACTION_FAILED
}

data class InspectedMediaInfo(
    val fileName: String = "",
    val formatLabel: String = "",
    val fileSizeBytes: Long = 0L,
    val durationMs: Long = 0L,
    val resolution: String = "",
    val frameRateFps: Float = 0f,
    val hasVideoTrack: Boolean = false,
    val hasAudioTrack: Boolean = false,
    val audioCodec: String = "",
    val audioSampleRateHz: Int = 24000,
    val audioChannels: Int = 1,
    val localSourcePath: String = "",
    val extractedWavPath: String = "",
    val errorMessage: String? = null
)

data class GuidedImportState(
    val step: Int = 1, // 1..5
    val existingProjectId: Long? = null,
    val projectTitle: String = "",
    val animeSeriesName: String = "",
    val sampleRateHz: Int = 24000,
    val preferredTtsEngine: String = "Android-Speech-TTS",
    val episodeNumber: Int = 1,
    val episodeTitle: String = "",
    val mediaStatus: MediaInspectionStatus = MediaInspectionStatus.IDLE,
    val inspectedMedia: InspectedMediaInfo? = null,
    val isCommitting: Boolean = false
)

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
    val dialogueTimestamps: Map<Long, Long>
)

@OptIn(ExperimentalCoroutinesApi::class)
class StudioViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AnimeDubDatabase.getInstance(application)
    private val dao = db.dao()
    val prefsRepo = StudioPreferencesRepository(application)
    val geminiKeyStore = GeminiApiKeyStore(application)
    val geminiClient = DirectGeminiApiClient(geminiKeyStore)
    val modelDownloadManager = ModelDownloadManager(application, dao)
    val coordinator = SevenSpecialistAgentsCoordinator(application, dao, geminiClient)
    val audioPlayer = StudioAudioPlayer(viewModelScope)

    // 5-Tab Primary Navigation + Contextual Production Section + Focused Overlays
    private val _activeTab = MutableStateFlow(MainTab.HOME)
    val activeTab: StateFlow<MainTab> = _activeTab.asStateFlow()

    private val _productionSection = MutableStateFlow(ProductionSection.PIPELINE)
    val productionSection: StateFlow<ProductionSection> = _productionSection.asStateFlow()

    private val _activeOverlay = MutableStateFlow(StudioOverlay.NONE)
    val activeOverlay: StateFlow<StudioOverlay> = _activeOverlay.asStateFlow()

    private val _selectedModelId = MutableStateFlow("silero_vad_onnx")
    val selectedModelId: StateFlow<String> = _selectedModelId.asStateFlow()

    private val _selectedDialogueId = MutableStateFlow<Long?>(null)
    val selectedDialogueId: StateFlow<Long?> = _selectedDialogueId.asStateFlow()

    private val _projectSearchQuery = MutableStateFlow("")
    val projectSearchQuery: StateFlow<String> = _projectSearchQuery.asStateFlow()

    private val _projectSortOrder = MutableStateFlow(ProjectSortOrder.RECENT)
    val projectSortOrder: StateFlow<ProjectSortOrder> = _projectSortOrder.asStateFlow()

    private val _guidedImport = MutableStateFlow(GuidedImportState())
    val guidedImport: StateFlow<GuidedImportState> = _guidedImport.asStateFlow()

    private val _pipelineBanner = MutableStateFlow(PipelineBannerState())
    val pipelineBanner: StateFlow<PipelineBannerState> = _pipelineBanner.asStateFlow()

    private var activePipelineJob: Job? = null

    private val _geminiKeyState = MutableStateFlow(geminiKeyStore.getCurrentState())
    val geminiKeyState: StateFlow<GeminiApiKeyState> = _geminiKeyState.asStateFlow()

    private val _geminiConnectionState = MutableStateFlow(
        if (geminiKeyStore.hasConfiguredKey()) GeminiConnectionState.API_KEY_SAVED_UNVERIFIED
        else GeminiConnectionState.API_KEY_MISSING
    )
    val geminiConnectionState: StateFlow<GeminiConnectionState> = _geminiConnectionState.asStateFlow()

    private val _geminiConnectionStatus = MutableStateFlow<GeminiConnectionTestResult?>(null)
    val geminiConnectionStatus: StateFlow<GeminiConnectionTestResult?> = _geminiConnectionStatus.asStateFlow()

    private val _isTestingGeminiConnection = MutableStateFlow(false)
    val isTestingGeminiConnection: StateFlow<Boolean> = _isTestingGeminiConnection.asStateFlow()

    private val _timelineZoom = MutableStateFlow(1.0f)
    val timelineZoom: StateFlow<Float> = _timelineZoom.asStateFlow()

    private val undoStack = ArrayDeque<UndoTimelineSnapshot>()
    private val redoStack = ArrayDeque<UndoTimelineSnapshot>()
    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()
    private val _canRedo = MutableStateFlow(false)
    val canRedo: StateFlow<Boolean> = _canRedo.asStateFlow()

    val playbackState: StateFlow<AudioPlaybackState> = audioPlayer.playbackState

    val studioConfig: StateFlow<StudioConfig> = prefsRepo.configFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), StudioConfig())

    private val _activeEpisodeId = MutableStateFlow(0L)
    val activeEpisodeId: StateFlow<Long> = _activeEpisodeId.asStateFlow()

    val projects: StateFlow<List<ProjectEntity>> = dao.observeProjects()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allEpisodes: StateFlow<List<EpisodeEntity>> = dao.observeAllEpisodes()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val activeEpisode: StateFlow<EpisodeEntity?> = _activeEpisodeId
        .flatMapLatest { dao.observeEpisodeById(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val activeProject: StateFlow<ProjectEntity?> = combine(projects, activeEpisode) { projList, ep ->
        if (ep != null) projList.find { it.id == ep.projectId } else projList.firstOrNull()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val audioStems: StateFlow<List<AudioStemEntity>> = _activeEpisodeId
        .flatMapLatest { dao.observeStemsForEpisode(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val transcripts: StateFlow<List<TranscriptSegmentEntity>> = _activeEpisodeId
        .flatMapLatest { dao.observeTranscriptsForEpisode(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val dialoguesChronological: StateFlow<List<DialogueSegmentEntity>> = _activeEpisodeId
        .flatMapLatest { dao.observeDialoguesChronological(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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

    val allExports: StateFlow<List<ExportRecordEntity>> = dao.observeAllExports()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        initializeCatalogAndRestoreActiveEpisode()
    }

    /**
     * Initializes ONLY the verified model manifest catalog and restores any genuine previously
     * created user project/episode from Room. Never auto-creates fake/sample projects!
     */
    private fun initializeCatalogAndRestoreActiveEpisode() {
        viewModelScope.launch(Dispatchers.IO) {
            val savedConfig = prefsRepo.configFlow.first()
            val keyState = geminiKeyStore.getCurrentState()
            _geminiKeyState.value = keyState
            val initialConnState = computeUnverifiedConnectionState(
                hasKey = keyState.isConfigured,
                modelName = savedConfig.geminiModelName
            )
            _geminiConnectionState.value = initialConnState
            modelDownloadManager.reconcileCatalogWithDisk(
                geminiApiConfigured = keyState.isConfigured,
                geminiModelName = savedConfig.geminiModelName,
                geminiConnectionState = initialConnState
            )

            val savedEp = if (savedConfig.activeEpisodeId > 0L) dao.getEpisodeById(savedConfig.activeEpisodeId) else null
            if (savedEp != null) {
                _activeEpisodeId.value = savedEp.id
            } else {
                val realProjects = dao.getProjectsOnce()
                val firstEp = realProjects.firstOrNull()?.let { proj ->
                    dao.observeEpisodesForProject(proj.id).first().firstOrNull()
                }
                _activeEpisodeId.value = firstEp?.id ?: 0L
            }
        }
    }

    // --- Navigation Helpers ---

    fun selectTab(tab: MainTab) {
        _activeOverlay.value = StudioOverlay.NONE
        _activeTab.value = tab
    }

    fun selectProductionSection(section: ProductionSection) {
        _activeOverlay.value = StudioOverlay.NONE
        _activeTab.value = MainTab.PRODUCTION
        _productionSection.value = section
    }

    fun openOverlay(overlay: StudioOverlay) {
        _activeOverlay.value = overlay
    }

    fun closeOverlay() {
        _activeOverlay.value = StudioOverlay.NONE
    }

    fun handleSystemBack(): Boolean {
        if (_activeOverlay.value != StudioOverlay.NONE) {
            _activeOverlay.value = StudioOverlay.NONE
            return true
        }
        if (_activeTab.value != MainTab.HOME) {
            _activeTab.value = MainTab.HOME
            return true
        }
        return false
    }

    fun openModelDetail(modelId: String) {
        _selectedModelId.value = modelId
        _activeOverlay.value = StudioOverlay.MODEL_DETAIL
    }

    fun openGuidedNewProject() {
        val cfg = studioConfig.value
        _guidedImport.value = GuidedImportState(
            step = 1,
            existingProjectId = null,
            sampleRateHz = cfg.defaultAudioSampleRateHz,
            preferredTtsEngine = cfg.selectedTtsEngine,
            episodeNumber = 1
        )
        _activeOverlay.value = StudioOverlay.GUIDED_IMPORT
    }

    fun openGuidedImportEpisode(project: ProjectEntity? = activeProject.value) {
        if (project == null) {
            openGuidedNewProject()
            return
        }
        val existingEps = allEpisodes.value.filter { it.projectId == project.id }
        val nextNum = (existingEps.maxOfOrNull { it.episodeNumber } ?: 0) + 1
        _guidedImport.value = GuidedImportState(
            step = 2,
            existingProjectId = project.id,
            projectTitle = project.title,
            animeSeriesName = project.animeSeriesName,
            sampleRateHz = project.masterSampleRateHz,
            preferredTtsEngine = project.preferredTtsEngine,
            episodeNumber = nextNum,
            episodeTitle = ""
        )
        _activeOverlay.value = StudioOverlay.GUIDED_IMPORT
    }

    fun updateGuidedImport(transform: (GuidedImportState) -> GuidedImportState) {
        _guidedImport.value = transform(_guidedImport.value)
    }

    fun setProjectSearchQuery(q: String) {
        _projectSearchQuery.value = q
    }

    fun setProjectSortOrder(order: ProjectSortOrder) {
        _projectSortOrder.value = order
    }

    fun selectActiveEpisode(episodeId: Long, projectId: Long, navigateToProduction: Boolean = false) {
        _activeEpisodeId.value = episodeId
        _selectedDialogueId.value = null
        audioPlayer.stop()
        viewModelScope.launch {
            prefsRepo.setActiveProjectAndEpisode(projectId, episodeId)
        }
        if (navigateToProduction) {
            _activeOverlay.value = StudioOverlay.NONE
            _activeTab.value = MainTab.PRODUCTION
            _productionSection.value = ProductionSection.PIPELINE
        }
    }

    fun deleteProject(projectId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val eps = dao.observeEpisodesForProject(projectId).first()
            dao.deleteProjectById(projectId)
            eps.forEach { ep ->
                val dir = coordinator.getEpisodeWorkspaceDir(ep.id)
                if (dir.exists()) dir.deleteRecursively()
            }
            if (eps.any { it.id == _activeEpisodeId.value }) {
                val remaining = dao.observeAllEpisodes().first()
                val nextEp = remaining.firstOrNull()
                _activeEpisodeId.value = nextEp?.id ?: 0L
                prefsRepo.setActiveProjectAndEpisode(nextEp?.projectId ?: 0L, nextEp?.id ?: 0L)
            }
        }
    }

    /**
     * Real Media Inspection for Step 3 & Step 4 of the Guided Import Flow.
     * Copies selected URI into staging directory, inspects genuine metadata using MediaMetadataRetriever
     * and MediaExtractor, and runs real AudioDspEngine.extractAudioTrackToWav().
     * Never fabricates duration, resolution, FPS, or audio!
     */
    fun inspectMediaUriForImport(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            _guidedImport.value = _guidedImport.value.copy(
                step = 4,
                mediaStatus = MediaInspectionStatus.LOADING,
                inspectedMedia = null
            )

            try {
                val app = getApplication<Application>()
                val resolver = app.contentResolver
                var displayName = "imported_media"
                var reportedSize = 0L

                resolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (nameIdx >= 0) displayName = cursor.getString(nameIdx) ?: displayName
                        if (sizeIdx >= 0) reportedSize = cursor.getLong(sizeIdx)
                    }
                }

                val stagingDir = File(app.filesDir, "import_staging").apply { mkdirs() }
                val safeName = displayName.replace(Regex("[^a-zA-Z0-9._\\-]"), "_")
                val stagedSource = File(stagingDir, "${System.currentTimeMillis()}_$safeName")

                val copied = resolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(stagedSource).use { output ->
                        input.copyTo(output)
                    }
                    true
                } ?: false

                if (!copied || !stagedSource.exists() || stagedSource.length() == 0L) {
                    _guidedImport.value = _guidedImport.value.copy(
                        mediaStatus = MediaInspectionStatus.EXTRACTION_FAILED,
                        inspectedMedia = InspectedMediaInfo(
                            fileName = displayName,
                            errorMessage = "Could not read the selected file from device storage."
                        )
                    )
                    return@launch
                }

                val actualSize = stagedSource.length().takeIf { it > 0L } ?: reportedSize
                val ext = stagedSource.extension.uppercase().ifBlank { "MEDIA" }

                var durationMs = 0L
                var width = 0
                var height = 0
                var frameRate = 0f
                var hasVideo = false
                var hasAudio = false
                var detectedAudioCodec = ""
                var detectedSampleRate = _guidedImport.value.sampleRateHz
                var detectedChannels = 1

                // 1. Inspect tracks with MediaExtractor
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(stagedSource.absolutePath)
                    for (i in 0 until extractor.trackCount) {
                        val fmt = extractor.getTrackFormat(i)
                        val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                        if (mime.startsWith("video/")) {
                            hasVideo = true
                            if (fmt.containsKey(MediaFormat.KEY_WIDTH)) width = fmt.getInteger(MediaFormat.KEY_WIDTH)
                            if (fmt.containsKey(MediaFormat.KEY_HEIGHT)) height = fmt.getInteger(MediaFormat.KEY_HEIGHT)
                            if (fmt.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                                frameRate = try {
                                    fmt.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat()
                                } catch (_: Exception) {
                                    try { fmt.getFloat(MediaFormat.KEY_FRAME_RATE) } catch (_: Exception) { 0f }
                                }
                            }
                            if (fmt.containsKey(MediaFormat.KEY_DURATION) && durationMs <= 0L) {
                                durationMs = fmt.getLong(MediaFormat.KEY_DURATION) / 1000L
                            }
                        } else if (mime.startsWith("audio/")) {
                            hasAudio = true
                            detectedAudioCodec = mime.substringAfter("audio/").uppercase()
                            if (fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                                detectedSampleRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            }
                            if (fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                                detectedChannels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            }
                            if (fmt.containsKey(MediaFormat.KEY_DURATION) && durationMs <= 0L) {
                                durationMs = fmt.getLong(MediaFormat.KEY_DURATION) / 1000L
                            }
                        }
                    }
                } catch (_: Exception) {
                } finally {
                    try { extractor.release() } catch (_: Exception) {}
                }

                // 2. Supplement with MediaMetadataRetriever
                val mmr = MediaMetadataRetriever()
                try {
                    mmr.setDataSource(stagedSource.absolutePath)
                    if (durationMs <= 0L) {
                        mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.let {
                            if (it > 0L) durationMs = it
                        }
                    }
                    if (width <= 0 || height <= 0) {
                        width = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: width
                        height = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: height
                    }
                    if (frameRate <= 0f) {
                        mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toFloatOrNull()?.let {
                            if (it > 0f) frameRate = it
                        }
                    }
                    if (mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes") hasVideo = true
                    if (mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes") hasAudio = true
                } catch (_: Exception) {
                } finally {
                    try { mmr.release() } catch (_: Exception) {}
                }

                val resolutionStr = if (width > 0 && height > 0) "${width}×${height}" else if (hasVideo) "Video (size unknown)" else "Audio-Only"

                // 3. Extract real PCM WAV master right now so inspection verifies genuine extractability
                val stagedWav = File(stagingDir, "${System.currentTimeMillis()}_master.wav")
                val extractRes = AudioDspEngine.extractAudioTrackToWav(
                    sourceFile = stagedSource,
                    outputWavFile = stagedWav,
                    targetSampleRateHz = _guidedImport.value.sampleRateHz
                )

                when (extractRes) {
                    is MediaExtractionResult.Success -> {
                        val finalDur = if (durationMs > 0L) durationMs else extractRes.durationMs
                        _guidedImport.value = _guidedImport.value.copy(
                            mediaStatus = MediaInspectionStatus.READY,
                            inspectedMedia = InspectedMediaInfo(
                                fileName = displayName,
                                formatLabel = ext,
                                fileSizeBytes = actualSize,
                                durationMs = finalDur,
                                resolution = resolutionStr,
                                frameRateFps = frameRate,
                                hasVideoTrack = hasVideo,
                                hasAudioTrack = true,
                                audioCodec = detectedAudioCodec.ifBlank { extractRes.audioCodec },
                                audioSampleRateHz = extractRes.sampleRateHz,
                                audioChannels = detectedChannels,
                                localSourcePath = stagedSource.absolutePath,
                                extractedWavPath = extractRes.wavFile.absolutePath,
                                errorMessage = null
                            )
                        )
                    }
                    MediaExtractionResult.MissingAudioTrack -> {
                        _guidedImport.value = _guidedImport.value.copy(
                            mediaStatus = MediaInspectionStatus.MISSING_AUDIO,
                            inspectedMedia = InspectedMediaInfo(
                                fileName = displayName,
                                formatLabel = ext,
                                fileSizeBytes = actualSize,
                                durationMs = durationMs,
                                resolution = resolutionStr,
                                frameRateFps = frameRate,
                                hasVideoTrack = hasVideo,
                                hasAudioTrack = false,
                                errorMessage = "This file has no audio track. Select a video or audio file containing spoken dialogue."
                            )
                        )
                    }
                    is MediaExtractionResult.UnsupportedFormat -> {
                        _guidedImport.value = _guidedImport.value.copy(
                            mediaStatus = MediaInspectionStatus.UNSUPPORTED,
                            inspectedMedia = InspectedMediaInfo(
                                fileName = displayName,
                                formatLabel = ext,
                                fileSizeBytes = actualSize,
                                durationMs = durationMs,
                                resolution = resolutionStr,
                                hasVideoTrack = hasVideo,
                                hasAudioTrack = hasAudio,
                                errorMessage = extractRes.reason
                            )
                        )
                    }
                    is MediaExtractionResult.Failure -> {
                        _guidedImport.value = _guidedImport.value.copy(
                            mediaStatus = MediaInspectionStatus.EXTRACTION_FAILED,
                            inspectedMedia = InspectedMediaInfo(
                                fileName = displayName,
                                formatLabel = ext,
                                fileSizeBytes = actualSize,
                                durationMs = durationMs,
                                resolution = resolutionStr,
                                hasVideoTrack = hasVideo,
                                hasAudioTrack = hasAudio,
                                errorMessage = extractRes.reason
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                _guidedImport.value = _guidedImport.value.copy(
                    mediaStatus = MediaInspectionStatus.EXTRACTION_FAILED,
                    inspectedMedia = InspectedMediaInfo(
                        errorMessage = e.localizedMessage ?: "Failed to inspect selected media."
                    )
                )
            }
        }
    }

    /**
     * Step 5: Persists the real Project + Episode + extracted PCM WAV master and optionally
     * starts initial analysis stages (Stems -> VAD Speech Slices -> Speaker Analysis -> Global IDs).
     */
    fun confirmGuidedImportAndStart(startProcessingNow: Boolean) {
        val state = _guidedImport.value
        val media = state.inspectedMedia
        if (state.mediaStatus != MediaInspectionStatus.READY || media == null) return

        viewModelScope.launch(Dispatchers.IO) {
            _guidedImport.value = state.copy(isCommitting = true)
            try {
                val projId = state.existingProjectId ?: dao.insertProject(
                    ProjectEntity(
                        title = state.projectTitle.trim().ifBlank { "Untitled Dub Project" },
                        animeSeriesName = state.animeSeriesName.trim().ifBlank { state.projectTitle.trim().ifBlank { "Anime Series" } },
                        masterSampleRateHz = state.sampleRateHz,
                        preferredTtsEngine = state.preferredTtsEngine
                    )
                )

                val cleanEpTitle = state.episodeTitle.trim().ifBlank { "Episode ${state.episodeNumber}" }
                val placeholderEpId = dao.insertEpisode(
                    EpisodeEntity(
                        projectId = projId,
                        episodeNumber = state.episodeNumber,
                        episodeTitle = cleanEpTitle,
                        videoFileName = media.fileName,
                        durationMs = media.durationMs,
                        resolution = media.resolution,
                        frameRateFps = media.frameRateFps,
                        fileSizeBytes = media.fileSizeBytes,
                        audioCodec = media.audioCodec,
                        audioSampleRateHz = media.audioSampleRateHz,
                        audioChannels = media.audioChannels,
                        originalVideoPath = media.localSourcePath,
                        originalMasterAudioPath = media.extractedWavPath,
                        currentStageNumber = 2,
                        currentStageName = "Stage 2 — Audio Extracted & Inspected",
                        completedStagesMask = (1 shl 1) or (1 shl 2),
                        pipelineStatus = "READY"
                    )
                )

                // Move staged files into dedicated episode workspace directory & clean staging copies
                val epDir = coordinator.getEpisodeWorkspaceDir(placeholderEpId)
                val stagedSrc = File(media.localSourcePath)
                val finalSrc = File(epDir, stagedSrc.name.substringAfter("_", stagedSrc.name))
                if (stagedSrc.exists()) {
                    stagedSrc.copyTo(finalSrc, overwrite = true)
                    if (stagedSrc.absolutePath != finalSrc.absolutePath) {
                        runCatching { stagedSrc.delete() }
                    }
                }

                val stagedWav = File(media.extractedWavPath)
                val finalWav = File(epDir, "master_original_pcm.wav")
                if (stagedWav.exists()) {
                    stagedWav.copyTo(finalWav, overwrite = true)
                    if (stagedWav.absolutePath != finalWav.absolutePath) {
                        runCatching { stagedWav.delete() }
                    }
                }

                val savedEp = dao.getEpisodeById(placeholderEpId)
                if (savedEp != null) {
                    dao.updateEpisode(
                        savedEp.copy(
                            originalVideoPath = finalSrc.absolutePath,
                            originalMasterAudioPath = finalWav.absolutePath,
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                }

                _activeEpisodeId.value = placeholderEpId
                prefsRepo.setActiveProjectAndEpisode(projId, placeholderEpId)

                _guidedImport.value = GuidedImportState()
                _activeOverlay.value = StudioOverlay.NONE
                _activeTab.value = MainTab.PRODUCTION
                _productionSection.value = ProductionSection.PIPELINE

                if (startProcessingNow) {
                    runPipelineStages(startStage = 3, endStage = 7)
                } else {
                    _pipelineBanner.value = PipelineBannerState(
                        statusBannerMessage = "Imported '${media.fileName}' (${media.durationMs / 1000}s). Ready to run pipeline."
                    )
                }
            } catch (e: Exception) {
                _guidedImport.value = _guidedImport.value.copy(isCommitting = false)
                _pipelineBanner.value = PipelineBannerState(
                    statusBannerMessage = "Failed to save episode: ${e.localizedMessage}",
                    isError = true
                )
            }
        }
    }

    /**
     * Runs a range of pipeline stages (e.g. 2..7 or single stage N..N) with live progress updates,
     * duplicate-run prevention, remote worker routing, and explicit cancellation support.
     */
    fun runPipelineStages(startStage: Int = 2, endStage: Int = 13) {
        val epId = _activeEpisodeId.value
        if (epId <= 0L) {
            _pipelineBanner.value = PipelineBannerState(
                statusBannerMessage = "Create a project and import an episode first.",
                isError = true
            )
            return
        }
        if (activePipelineJob?.isActive == true) {
            _pipelineBanner.value = _pipelineBanner.value.copy(
                statusBannerMessage = "A pipeline task is already running. Wait or tap Cancel first.",
                isError = false
            )
            return
        }
        val cfg = studioConfig.value
        val ttsEngine = cfg.selectedTtsEngine
        val geminiModel = cfg.geminiModelName
        activePipelineJob = viewModelScope.launch {
            _pipelineBanner.value = PipelineBannerState(
                isRunning = true,
                activeStageNumber = startStage,
                activeStageTitle = "Starting Stage $startStage…",
                activeDetail = "Initializing Android-local + Direct Gemini API pipeline…"
            )
            val result = coordinator.executePipelineFromStage(
                episodeId = epId,
                startStage = startStage,
                endStage = endStage,
                selectedTtsEngine = ttsEngine,
                geminiModelName = geminiModel
            ) { stageNum, stageTitle, detail ->
                _pipelineBanner.value = PipelineBannerState(
                    isRunning = true,
                    activeStageNumber = stageNum,
                    activeStageTitle = stageTitle,
                    activeDetail = detail
                )
            }
            result.fold(
                onSuccess = { stageRes ->
                    val ep = stageRes.episode
                    when (stageRes.status) {
                        StageStatus.COMPLETED -> {
                            _pipelineBanner.value = PipelineBannerState(
                                isRunning = false,
                                activeStageNumber = ep.currentStageNumber,
                                activeStageTitle = ep.currentStageName,
                                statusBannerMessage = stageRes.message.ifBlank { "Completed through ${ep.currentStageName}." },
                                isError = false
                            )
                        }
                        StageStatus.PARTIAL -> {
                            _pipelineBanner.value = PipelineBannerState(
                                isRunning = false,
                                activeStageNumber = stageRes.stageNumber,
                                activeStageTitle = ep.currentStageName,
                                statusBannerMessage = "Stage ${stageRes.stageNumber} PARTIAL (${stageRes.completedCount}/${stageRes.totalCount}): ${stageRes.message}",
                                isError = true
                            )
                        }
                        else -> {
                            _pipelineBanner.value = PipelineBannerState(
                                isRunning = false,
                                activeStageNumber = stageRes.stageNumber,
                                activeStageTitle = ep.currentStageName,
                                statusBannerMessage = "Stage ${stageRes.stageNumber} ${stageRes.status.name}: ${stageRes.message}",
                                isError = true
                            )
                        }
                    }
                },
                onFailure = { err ->
                    _pipelineBanner.value = PipelineBannerState(
                        isRunning = false,
                        statusBannerMessage = err.localizedMessage ?: "Pipeline stage failed.",
                        isError = true
                    )
                }
            )
        }
    }

    fun cancelPipeline() {
        val job = activePipelineJob
        if (job != null && job.isActive) {
            job.cancel()
            activePipelineJob = null
            _pipelineBanner.value = PipelineBannerState(
                isRunning = false,
                statusBannerMessage = "Pipeline cancelled. Completed stages remain saved for resume.",
                isError = false
            )
        }
    }

    /**
     * Inline transcript & timing edit — keeps TranscriptSegmentEntity and DialogueSegmentEntity in sync.
     */
    fun updateDialogueTranscriptAndTiming(
        dialogue: DialogueSegmentEntity,
        newJapaneseText: String,
        newRomajiReading: String,
        newStartMs: Long,
        newEndMs: Long
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val safeStart = newStartMs.coerceAtLeast(0L)
            val safeEnd = newEndMs.coerceAtLeast(safeStart + 200L)
            val ep = dao.getEpisodeById(dialogue.episodeId)
            var slicePath = dialogue.originalAudioSlicePath

            // Re-slice real vocal audio if timestamps changed
            if (ep != null && (safeStart != dialogue.originalStartMs || safeEnd != dialogue.originalEndMs)) {
                val stems = dao.getStemsForEpisodeOnce(ep.id)
                val vocalFile = stems.find { it.stemType == "DIALOGUE_VOCAL" }?.let { File(it.filePath) }
                    ?.takeIf { it.exists() }
                    ?: File(ep.originalMasterAudioPath).takeIf { it.exists() }
                if (vocalFile != null) {
                    val buf = AudioDspEngine.readWavFile(vocalFile)
                    val sliced = AudioDspEngine.sliceBuffer(buf, safeStart, safeEnd)
                    val out = File(coordinator.getEpisodeWorkspaceDir(ep.id), "jp_slices/jp_seg_%03d.wav".format(dialogue.globalOrderNumber))
                    AudioDspEngine.writeWavFile(out, sliced)
                    slicePath = out.absolutePath
                }
            }

            val timingChanged = safeStart != dialogue.originalStartMs || safeEnd != dialogue.originalEndMs
            dao.updateDialogue(
                dialogue.copy(
                    japaneseText = newJapaneseText.trim(),
                    originalStartMs = safeStart,
                    originalEndMs = safeEnd,
                    originalDurationMs = safeEnd - safeStart,
                    timelineStartMs = safeStart,
                    timingStatus = if (timingChanged) "MANUAL" else dialogue.timingStatus,
                    originalAudioSlicePath = slicePath
                )
            )

            val transcriptsList = dao.getTranscriptsForEpisodeOnce(dialogue.episodeId)
            val matchingTr = transcriptsList.find { it.id == dialogue.transcriptSegmentId }
                ?: transcriptsList.find { it.segmentIndex == dialogue.globalOrderNumber }
            if (matchingTr != null) {
                dao.updateTranscriptSegment(
                    matchingTr.copy(
                        japaneseText = newJapaneseText.trim(),
                        romajiReading = newRomajiReading.trim(),
                        preliminaryStartMs = safeStart,
                        preliminaryEndMs = safeEnd,
                        originalAudioSlicePath = slicePath
                    )
                )
            }
        }
    }

    /**
     * Adds a genuine user-authored dialogue segment with real audio slice extraction from the episode.
     */
    fun addManualDialogueSegment(
        japaneseText: String,
        romajiReading: String,
        startMs: Long,
        endMs: Long,
        speakerLabel: String = "A"
    ) {
        val epId = _activeEpisodeId.value
        if (epId <= 0L) return
        viewModelScope.launch(Dispatchers.IO) {
            val ep = dao.getEpisodeById(epId) ?: return@launch
            val safeStart = startMs.coerceIn(0L, ep.durationMs.coerceAtLeast(1000L))
            val safeEnd = endMs.coerceAtLeast(safeStart + 300L)
            val existing = dao.getDialoguesChronologicalOnce(epId)
            val nextOrder = (existing.maxOfOrNull { it.globalOrderNumber } ?: 0) + 1
            val cleanSpeaker = speakerLabel.trim().uppercase().ifBlank { "A" }

            val stems = dao.getStemsForEpisodeOnce(epId)
            val sourceAudio = stems.find { it.stemType == "DIALOGUE_VOCAL" }?.let { File(it.filePath) }
                ?.takeIf { it.exists() }
                ?: File(ep.originalMasterAudioPath).takeIf { it.exists() }

            var slicePath = ""
            if (sourceAudio != null) {
                val buf = AudioDspEngine.readWavFile(sourceAudio)
                val sliced = AudioDspEngine.sliceBuffer(buf, safeStart, safeEnd)
                if (sliced.samples.isNotEmpty()) {
                    val out = File(coordinator.getEpisodeWorkspaceDir(epId), "jp_slices/jp_seg_%03d.wav".format(nextOrder))
                    AudioDspEngine.writeWavFile(out, sliced)
                    slicePath = out.absolutePath
                }
            }

            val tr = TranscriptSegmentEntity(
                episodeId = epId,
                segmentIndex = nextOrder,
                japaneseText = japaneseText.trim(),
                romajiReading = romajiReading.trim(),
                preliminaryStartMs = safeStart,
                preliminaryEndMs = safeEnd,
                confidenceScore = -1f,
                asrModelUsed = "Manual User Entry",
                originalAudioSlicePath = slicePath
            )
            dao.insertTranscriptSegments(listOf(tr))
            val savedTr = dao.getTranscriptsForEpisodeOnce(epId).lastOrNull()

            dao.insertDialogues(
                listOf(
                    DialogueSegmentEntity(
                        episodeId = epId,
                        globalOrderNumber = nextOrder,
                        dialogueId = "$cleanSpeaker-%03d".format(nextOrder),
                        speakerLabel = cleanSpeaker,
                        originalStartMs = safeStart,
                        originalEndMs = safeEnd,
                        originalDurationMs = safeEnd - safeStart,
                        transcriptSegmentId = savedTr?.id ?: 0L,
                        japaneseText = japaneseText.trim(),
                        romanHinglishText = "",
                        ttsNormalizedText = "",
                        alignmentSource = "Manual Timeline Entry",
                        timingStatus = "MANUAL",
                        scriptValidationStatus = "UNVALIDATED",
                        originalAudioSlicePath = slicePath,
                        timelineStartMs = safeStart
                    )
                )
            )
            // Re-run Stage 7 numbering so global order stays strictly chronological
            coordinator.runStage7GlobalDialogueNumbering(ep)
        }
    }

    fun updateDialogueTranslationOnly(
        dialogue: DialogueSegmentEntity,
        newMixedScriptText: String,
        newEmotion: String,
        newSpeakingStyle: String
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val ep = dao.getEpisodeById(dialogue.episodeId)
            val project = ep?.let { dao.getProjectById(it.projectId) }
            val resolvedDicts = MixedScriptLanguageEngine.resolveScopedDictionaries(
                allEntries = dao.getAllDictionaryEntriesOnce(),
                projectId = ep?.projectId,
                animeSeriesName = project?.animeSeriesName
            )
            val cleanText = newMixedScriptText.trim()
            val validation = MixedScriptLanguageEngine.validateMixedScript(cleanText)
            val ttsPronunciation = MixedScriptLanguageEngine.buildTtsPronunciationText(cleanText, resolvedDicts)
            dao.updateDialogue(
                dialogue.copy(
                    romanHinglishText = cleanText,
                    ttsNormalizedText = ttsPronunciation,
                    scriptValidationStatus = validation.status,
                    emotion = newEmotion,
                    speakingStyle = newSpeakingStyle.trim().ifBlank { dialogue.speakingStyle },
                    generationStatus = if (cleanText.isNotBlank() && dialogue.generatedAudioPath == null) "TRANSLATED" else dialogue.generationStatus,
                    translationVersion = dialogue.translationVersion + 1,
                    errorNote = if (validation.isValid) null else validation.issueSummary
                )
            )
        }
    }

    fun fixDialogueScriptWithGemini(
        dialogueId: Long,
        currentText: String,
        onResult: (Result<String>) -> Unit
    ) {
        val modelName = studioConfig.value.geminiModelName
        viewModelScope.launch {
            _pipelineBanner.value = PipelineBannerState(
                isRunning = true,
                activeStageNumber = 8,
                activeStageTitle = "Fixing Mixed-Script with Gemini",
                activeDetail = "Converting Romanized Hindi to Devanagari while preserving English words…"
            )
            val res = coordinator.fixSingleDialogueScriptWithGemini(
                dialogueId = dialogueId,
                rawText = currentText,
                geminiModelName = modelName
            )
            _pipelineBanner.value = res.fold(
                onSuccess = {
                    PipelineBannerState(
                        isRunning = false,
                        statusBannerMessage = "Script converted to Mixed-Script Hindi-English (Devanagari + English)."
                    )
                },
                onFailure = { err ->
                    PipelineBannerState(
                        isRunning = false,
                        statusBannerMessage = err.localizedMessage ?: "Script fix failed.",
                        isError = true
                    )
                }
            )
            onResult(res)
        }
    }

    fun reviewOrRewriteDialogueWithGemini(
        dialogueId: Long,
        currentText: String,
        instruction: String,
        onResult: (Result<GeminiDialogueRewriteResult>) -> Unit
    ) {
        val modelName = studioConfig.value.geminiModelName
        viewModelScope.launch {
            _pipelineBanner.value = PipelineBannerState(
                isRunning = true,
                activeStageNumber = 8,
                activeStageTitle = "Gemini Dialogue Review & Rewrite",
                activeDetail = "Polishing mixed-script Hindi-English line with scene context & dictionary…"
            )
            val res = coordinator.reviewOrRewriteDialogueWithGemini(
                dialogueId = dialogueId,
                currentMixedScript = currentText,
                instruction = instruction,
                geminiModelName = modelName
            )
            _pipelineBanner.value = res.fold(
                onSuccess = { rw ->
                    PipelineBannerState(
                        isRunning = false,
                        statusBannerMessage = rw.reviewNotes.ifBlank { "Dialogue reviewed and polished via Direct Gemini API." }
                    )
                },
                onFailure = { err ->
                    PipelineBannerState(
                        isRunning = false,
                        statusBannerMessage = err.localizedMessage ?: "Gemini review failed.",
                        isError = true
                    )
                }
            )
            onResult(res)
        }
    }

    fun renameSpeakerCharacter(speaker: SpeakerProfileEntity, newCharacterName: String, consentConfirmed: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val cleanName = newCharacterName.trim().ifBlank { "Speaker ${speaker.speakerLabel}" }
            dao.updateSpeakerProfile(
                speaker.copy(
                    characterName = cleanName,
                    rightsConsentConfirmed = consentConfirmed,
                    useConsentedGenericVoiceFallback = !consentConfirmed
                )
            )
            val tracks = dao.getTimelineTracksOnce(speaker.episodeId)
            tracks.find { it.speakerLabel == speaker.speakerLabel }?.let { track ->
                dao.updateTimelineTrack(track.copy(trackTitle = "${speaker.speakerLabel} • $cleanName"))
            }
        }
    }

    fun reassignDialogueSpeaker(dialogue: DialogueSegmentEntity, newSpeakerLabel: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val cleanLabel = newSpeakerLabel.trim().uppercase().ifBlank { "UNIDENTIFIED" }
            val ep = dao.getEpisodeById(dialogue.episodeId) ?: return@launch
            val newDialogueId = "$cleanLabel-%03d".format(dialogue.globalOrderNumber)
            val updated = dialogue.copy(
                speakerLabel = cleanLabel,
                dialogueId = newDialogueId,
                voiceReferenceId = if (cleanLabel == "UNIDENTIFIED") "" else "VREF-${ep.projectId}-$cleanLabel"
            )
            dao.updateDialogue(updated)
            if (updated.romanHinglishText.isNotBlank() && !updated.generatedAudioPath.isNullOrBlank()) {
                val cfg = studioConfig.value
                coordinator.regenerateSingleDialogueOnly(
                    dialogueId = updated.id,
                    selectedTtsEngine = cfg.selectedTtsEngine
                )
            }
        }
    }

    fun updateAndRegenerateSingleDialogue(
        dialogueId: Long,
        newRomanHinglish: String,
        newEmotion: String
    ) {
        val cfg = studioConfig.value
        viewModelScope.launch {
            _pipelineBanner.value = PipelineBannerState(
                isRunning = true,
                activeStageNumber = 10,
                activeStageTitle = "Synthesizing Dialogue Line (Local Android TTS)",
                activeDetail = "Generating spoken Hindi/English clip & refreshing mix…"
            )
            val res = coordinator.regenerateSingleDialogueOnly(
                dialogueId = dialogueId,
                newRomanHinglishText = newRomanHinglish,
                newEmotion = newEmotion,
                selectedTtsEngine = cfg.selectedTtsEngine
            )
            _pipelineBanner.value = res.fold(
                onSuccess = { dlg ->
                    PipelineBannerState(
                        isRunning = false,
                        statusBannerMessage = "Updated ${dlg.dialogueId} (${dlg.actualGeneratedDurationMs} ms, ${dlg.syncStatus}, ${dlg.ttsEngineUsed})."
                    )
                },
                onFailure = { err ->
                    PipelineBannerState(
                        isRunning = false,
                        statusBannerMessage = err.localizedMessage ?: "Synthesis failed.",
                        isError = true
                    )
                }
            )
        }
    }

    fun applyDictionaryScopedChange(
        targetDialogueId: Long?,
        japaneseTerm: String,
        oldTermOrWord: String,
        newRomanHinglishTerm: String,
        pronunciationGuidance: String,
        scope: String
    ) {
        val epId = _activeEpisodeId.value
        if (epId <= 0L) return
        val cfg = studioConfig.value
        viewModelScope.launch {
            _pipelineBanner.value = PipelineBannerState(
                isRunning = true,
                activeStageNumber = 8,
                activeStageTitle = "Applying Dictionary Rule ($scope)",
                activeDetail = "Updating affected dialogues…"
            )
            val count = coordinator.applyScopedDictionaryReplacement(
                episodeId = epId,
                targetDialogueId = targetDialogueId,
                japaneseTerm = japaneseTerm,
                oldTermOrWord = oldTermOrWord,
                newRomanHinglishTerm = newRomanHinglishTerm,
                pronunciationGuidance = pronunciationGuidance,
                scope = scope,
                selectedTtsEngine = cfg.selectedTtsEngine
            )
            _pipelineBanner.value = PipelineBannerState(
                isRunning = false,
                statusBannerMessage = "Saved '$newRomanHinglishTerm' ($scope). Updated $count affected dialogue(s)."
            )
        }
    }

    fun addOrUpdateDictionaryEntry(entry: DictionaryEntryEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            val normalizedLevel = entry.dictionaryLevel.uppercase().let {
                if (it in setOf("PROJECT", "ANIME", "GLOBAL")) it else "GLOBAL"
            }
            val cleanPreferred = MixedScriptLanguageEngine.preferredDisplayTerm(entry)
            val cleanSource = entry.japaneseSourceTerm.trim()
            if (cleanSource.isBlank() && cleanPreferred.isBlank()) return@launch

            val targetProjectId = if (normalizedLevel == "PROJECT") entry.projectId else null
            val targetAnimeName = if (normalizedLevel == "ANIME") entry.animeSeriesName?.trim()?.takeIf { it.isNotBlank() } else null
            val key = MixedScriptLanguageEngine.normalizedTermKey(cleanSource, cleanPreferred)

            val allExisting = dao.getAllDictionaryEntriesOnce()
            val duplicate = allExisting.find { existing ->
                existing.id != entry.id &&
                    existing.dictionaryLevel.equals(normalizedLevel, ignoreCase = true) &&
                    existing.projectId == targetProjectId &&
                    (existing.animeSeriesName?.trim() ?: "").equals(targetAnimeName ?: "", ignoreCase = true) &&
                    MixedScriptLanguageEngine.normalizedTermKey(
                        existing.japaneseSourceTerm,
                        MixedScriptLanguageEngine.preferredDisplayTerm(existing)
                    ) == key
            }

            val finalId = if (entry.id > 0L) entry.id else (duplicate?.id ?: 0L)
            val sanitized = entry.copy(
                id = finalId,
                dictionaryLevel = normalizedLevel,
                projectId = targetProjectId,
                animeSeriesName = targetAnimeName,
                japaneseSourceTerm = cleanSource,
                preferredMixedScript = entry.preferredMixedScript.trim().ifBlank { cleanPreferred },
                preferredRomanHinglish = entry.preferredMixedScript.trim().ifBlank { cleanPreferred },
                preferredHindiWording = entry.preferredHindiWording.trim(),
                preferredEnglishWording = entry.preferredEnglishWording.trim(),
                pronunciationGuidance = entry.pronunciationGuidance.trim(),
                contextNotes = entry.contextNotes.trim(),
                updatedAt = System.currentTimeMillis()
            )
            dao.insertDictionaryEntry(sanitized)
            _pipelineBanner.value = PipelineBannerState(
                isRunning = false,
                statusBannerMessage = if (finalId > 0L && entry.id == 0L) {
                    "Updated existing $normalizedLevel dictionary entry for '${cleanSource.ifBlank { cleanPreferred }}'."
                } else if (entry.id > 0L) {
                    "Saved changes to $normalizedLevel dictionary entry '${cleanSource.ifBlank { cleanPreferred }}'."
                } else {
                    "Added $normalizedLevel dictionary term '${cleanSource.ifBlank { cleanPreferred }}' → '$cleanPreferred'."
                }
            )
        }
    }

    fun deleteDictionaryEntry(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.deleteDictionaryEntry(id)
        }
    }

    fun exportDictionariesToJson(): String {
        return MixedScriptLanguageEngine.exportDictionaryEntriesToJson(dictionaries.value)
    }

    fun importDictionariesFromJson(
        json: String,
        onDone: (Result<Int>) -> Unit = {}
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val activeProj = activeProject.value
            val parsed = MixedScriptLanguageEngine.parseDictionaryEntriesFromJson(
                json = json,
                fallbackProjectId = activeProj?.id,
                fallbackAnimeSeriesName = activeProj?.animeSeriesName ?: activeProj?.title ?: ""
            )
            if (parsed.isFailure) {
                val err = parsed.exceptionOrNull()!!
                _pipelineBanner.value = PipelineBannerState(
                    isRunning = false,
                    statusBannerMessage = err.localizedMessage ?: "Dictionary JSON import failed.",
                    isError = true
                )
                onDone(Result.failure(err))
                return@launch
            }
            val incoming = parsed.getOrThrow()
            val existingAll = dao.getAllDictionaryEntriesOnce()
            val plan = MixedScriptLanguageEngine.planDictionaryImport(
                incomingEntries = incoming,
                existingEntries = existingAll
            )
            dao.applyDictionaryImportPlanAtomic(
                toInsert = plan.toInsert,
                toUpdate = plan.toUpdate
            )
            val totalWritten = plan.importedCount + plan.updatedCount
            val summary = "Dictionary import complete — Imported: ${plan.importedCount}, Updated: ${plan.updatedCount}, Skipped: ${plan.skippedCount}, Failed: ${plan.failedCount}."
            _pipelineBanner.value = PipelineBannerState(
                isRunning = false,
                statusBannerMessage = summary,
                isError = totalWritten == 0 && plan.failedCount > 0
            )
            onDone(Result.success(totalWritten))
        }
    }

    fun updateStemControls(stem: AudioStemEntity, volumeGain: Float, isMuted: Boolean, isSolo: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.updateStem(
                stem.copy(
                    volumeGain = volumeGain.coerceIn(0f, 1.5f),
                    isMuted = isMuted,
                    isSolo = isSolo
                )
            )
            val ep = dao.getEpisodeById(stem.episodeId)
            if (ep != null) {
                try { coordinator.runStage12Agent7MultitrackMixing(ep) } catch (_: Exception) {}
            }
        }
    }

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
                try { coordinator.runStage12Agent7MultitrackMixing(ep) } catch (_: Exception) {}
            }
        }
    }

    fun selectDialogue(dialogueId: Long?) {
        _selectedDialogueId.value = dialogueId
    }

    fun setTimelineZoom(zoom: Float) {
        _timelineZoom.value = zoom.coerceIn(0.5f, 3.0f)
    }

    fun adjustDialogueTimelinePosition(dialogue: DialogueSegmentEntity, deltaMs: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val all = dao.getDialoguesChronologicalOnce(dialogue.episodeId)
            undoStack.addLast(
                UndoTimelineSnapshot(
                    description = "Move ${dialogue.dialogueId}",
                    dialogueTimestamps = all.associate { it.id to it.timelineStartMs }
                )
            )
            redoStack.clear()
            _canUndo.value = undoStack.isNotEmpty()
            _canRedo.value = false

            val newStart = (dialogue.timelineStartMs + deltaMs).coerceAtLeast(0L)
            dao.updateDialogue(dialogue.copy(timelineStartMs = newStart))
            val ep = dao.getEpisodeById(dialogue.episodeId)
            if (ep != null) {
                try { coordinator.runStage12Agent7MultitrackMixing(ep) } catch (_: Exception) {}
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
                    description = "Redo",
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
            dao.getEpisodeById(epId)?.let { try { coordinator.runStage12Agent7MultitrackMixing(it) } catch (_: Exception) {} }
        }
    }

    fun redoTimelineEdit() {
        if (redoStack.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val epId = _activeEpisodeId.value
            val currentAll = dao.getDialoguesChronologicalOnce(epId)
            undoStack.addLast(
                UndoTimelineSnapshot(
                    description = "Undo",
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
            dao.getEpisodeById(epId)?.let { try { coordinator.runStage12Agent7MultitrackMixing(it) } catch (_: Exception) {} }
        }
    }

    fun remediateQcTarget(targetKey: String) {
        when (targetKey.uppercase()) {
            "TIMELINE", "MULTITRACKTIMELINE" -> selectTab(MainTab.TIMELINE)
            "TRANSCRIPT", "DIALOGUESEGMENTATION" -> selectProductionSection(ProductionSection.TRANSCRIPT)
            "SPEAKERS", "CHARACTERVOICEPROFILES" -> selectProductionSection(ProductionSection.SPEAKERS)
            "TRANSLATION", "TRANSLATIONSTUDIO" -> selectProductionSection(ProductionSection.TRANSLATION)
            "VOICE_GEN", "VOICEGENERATIONSTUDIO", "CHARACTERBATCHQUEUE" -> selectProductionSection(ProductionSection.VOICE_GEN)
            "AUDIO_STEMS", "BACKGROUNDAUDIOSTUDIO", "AUDIOSTEMMANAGER" -> selectProductionSection(ProductionSection.AUDIO_STEMS)
            else -> selectProductionSection(ProductionSection.QC_EXPORT)
        }
    }

    fun downloadModel(modelId: String) {
        viewModelScope.launch {
            val res = modelDownloadManager.downloadAndVerifyModel(modelId)
            if (res.isFailure) {
                _pipelineBanner.value = PipelineBannerState(
                    isRunning = false,
                    statusBannerMessage = res.exceptionOrNull()?.localizedMessage ?: "Model download failed.",
                    isError = true
                )
            }
        }
    }

    fun cancelModelDownload(modelId: String) {
        modelDownloadManager.cancelDownload(modelId)
    }

    fun verifyModelIntegrity(modelId: String) {
        viewModelScope.launch {
            modelDownloadManager.verifyInstalledModelIntegrity(modelId)
        }
    }

    fun deleteModel(modelId: String) {
        viewModelScope.launch {
            modelDownloadManager.deleteInstalledModel(modelId)
        }
    }

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
        if (epId <= 0L) return
        viewModelScope.launch {
            _pipelineBanner.value = PipelineBannerState(
                isRunning = true,
                activeStageNumber = 14,
                activeStageTitle = "Exporting ${if (exportFormat == "MP4_VIDEO") "MP4 Video" else "WAV Audio"}",
                activeDetail = "Writing and verifying output file on disk…"
            )
            try {
                val ep = dao.getEpisodeById(epId)
                    ?: throw IllegalStateException("Active episode not found.")
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
                    statusBannerMessage = "Export & subtitles verified: ${exportedEp.exportedVideoPath?.substringAfterLast('/')}"
                )
            } catch (e: Exception) {
                _pipelineBanner.value = PipelineBannerState(
                    isRunning = false,
                    statusBannerMessage = e.localizedMessage ?: "Export failed.",
                    isError = true
                )
            }
        }
    }

    fun exportSubtitlesOnly(customFileName: String) {
        val epId = _activeEpisodeId.value
        if (epId <= 0L) return
        viewModelScope.launch {
            _pipelineBanner.value = PipelineBannerState(
                isRunning = true,
                activeStageNumber = 14,
                activeStageTitle = "Generating Subtitles (Agent 21)",
                activeDetail = "Writing & validating Japanese SRT, Mixed-Script Hindi-English SRT, Bilingual SRT, and Styled ASS…"
            )
            try {
                val ep = dao.getEpisodeById(epId)
                    ?: throw IllegalStateException("Active episode not found.")
                val bundle = coordinator.generateSubtitlesOnly(ep, customFileName)
                _pipelineBanner.value = PipelineBannerState(
                    isRunning = false,
                    statusBannerMessage = if (bundle.validationPassed) {
                        "Validated 4 subtitle files (${bundle.cueCount} cues): ${bundle.hindiHinglishSrtFile.name} & ${bundle.styledAssFile.name}"
                    } else {
                        "Subtitle validation warnings: ${bundle.validationIssues.joinToString("; ")}"
                    },
                    isError = !bundle.validationPassed
                )
            } catch (e: Exception) {
                _pipelineBanner.value = PipelineBannerState(
                    isRunning = false,
                    statusBannerMessage = e.localizedMessage ?: "Subtitle export failed.",
                    isError = true
                )
            }
        }
    }

    // --- Direct Gemini API Key & Connection Management ---

    private fun computeUnverifiedConnectionState(hasKey: Boolean, modelName: String): GeminiConnectionState {
        return when {
            !hasKey -> GeminiConnectionState.API_KEY_MISSING
            DirectGeminiApiClient.validateModelSelection(modelName).isFailure -> GeminiConnectionState.MODEL_UNAVAILABLE
            else -> GeminiConnectionState.API_KEY_SAVED_UNVERIFIED
        }
    }

    fun saveGeminiApiKey(rawKey: String, onDone: (Result<GeminiApiKeyState>) -> Unit = {}) {
        viewModelScope.launch(Dispatchers.IO) {
            val res = geminiKeyStore.saveApiKey(rawKey)
            res.fold(
                onSuccess = { newState ->
                    val currentModel = studioConfig.value.geminiModelName
                    val nextConnState = computeUnverifiedConnectionState(newState.isConfigured, currentModel)
                    _geminiKeyState.value = newState
                    // Immediately invalidate prior verification status when key changes
                    _geminiConnectionStatus.value = null
                    _geminiConnectionState.value = nextConnState
                    modelDownloadManager.updateDirectGeminiCatalogStatus(
                        connectionState = nextConnState,
                        modelName = currentModel
                    )
                    _pipelineBanner.value = PipelineBannerState(
                        isRunning = false,
                        statusBannerMessage = "Gemini API key saved encrypted in Android Keystore (unverified). Tap Test Connection to verify."
                    )
                },
                onFailure = { err ->
                    _geminiKeyState.value = geminiKeyStore.getCurrentState()
                    _pipelineBanner.value = PipelineBannerState(
                        isRunning = false,
                        statusBannerMessage = err.localizedMessage ?: "Invalid Gemini API key.",
                        isError = true
                    )
                }
            )
            onDone(res)
        }
    }

    fun removeGeminiApiKey() {
        viewModelScope.launch(Dispatchers.IO) {
            val newState = geminiKeyStore.removeApiKey()
            val currentModel = studioConfig.value.geminiModelName
            val nextConnState = computeUnverifiedConnectionState(newState.isConfigured, currentModel)
            _geminiKeyState.value = newState
            _geminiConnectionStatus.value = null
            _geminiConnectionState.value = nextConnState
            modelDownloadManager.updateDirectGeminiCatalogStatus(
                connectionState = nextConnState,
                modelName = currentModel
            )
            _pipelineBanner.value = PipelineBannerState(
                isRunning = false,
                statusBannerMessage = if (newState.isConfigured) {
                    "Removed custom key; falling back to build-configured GEMINI_API_KEY (unverified)."
                } else {
                    "Gemini API key removed from Android Keystore."
                }
            )
        }
    }

    fun updateSelectedGeminiModel(modelName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val normalized = DirectGeminiApiClient.normalizeModelName(modelName)
            prefsRepo.updateGeminiModelName(normalized)
            // Immediately invalidate prior connection verification when selected model changes!
            _geminiConnectionStatus.value = null
            val hasKey = geminiKeyStore.hasConfiguredKey()
            val nextConnState = computeUnverifiedConnectionState(hasKey, normalized)
            _geminiConnectionState.value = nextConnState
            val validation = DirectGeminiApiClient.validateModelSelection(normalized)
            modelDownloadManager.updateDirectGeminiCatalogStatus(
                connectionState = nextConnState,
                modelName = normalized,
                connectionMessage = validation.exceptionOrNull()?.localizedMessage
            )
            if (validation.isFailure) {
                _pipelineBanner.value = PipelineBannerState(
                    isRunning = false,
                    statusBannerMessage = validation.exceptionOrNull()?.localizedMessage ?: "Selected model is unavailable.",
                    isError = true
                )
            }
        }
    }

    fun testGeminiConnection(modelName: String = studioConfig.value.geminiModelName) {
        if (_isTestingGeminiConnection.value) return
        viewModelScope.launch {
            val normalized = DirectGeminiApiClient.normalizeModelName(modelName)
            _isTestingGeminiConnection.value = true
            _geminiConnectionState.value = GeminiConnectionState.TESTING
            modelDownloadManager.updateDirectGeminiCatalogStatus(
                connectionState = GeminiConnectionState.TESTING,
                modelName = normalized
            )
            val res = geminiClient.testConnection(normalized)
            res.fold(
                onSuccess = { ok ->
                    _geminiConnectionStatus.value = ok
                    _geminiConnectionState.value = GeminiConnectionState.VERIFIED_CONNECTED
                    modelDownloadManager.updateDirectGeminiCatalogStatus(
                        connectionState = GeminiConnectionState.VERIFIED_CONNECTED,
                        modelName = ok.modelId,
                        connectionMessage = ok.message
                    )
                    _pipelineBanner.value = PipelineBannerState(
                        isRunning = false,
                        statusBannerMessage = ok.message
                    )
                },
                onFailure = { err ->
                    val kind = (err as? GeminiApiException)?.kind
                    val errConnState = DirectGeminiApiClient.mapErrorToConnectionState(kind)
                    val msg = err.localizedMessage ?: "Gemini connection test failed."
                    _geminiConnectionState.value = errConnState
                    _geminiConnectionStatus.value = GeminiConnectionTestResult(
                        isConnected = false,
                        modelId = normalized,
                        remoteDisplayName = "",
                        latencyMs = 0L,
                        message = msg,
                        errorKind = kind,
                        connectionState = errConnState
                    )
                    modelDownloadManager.updateDirectGeminiCatalogStatus(
                        connectionState = errConnState,
                        modelName = normalized,
                        connectionMessage = msg
                    )
                    _pipelineBanner.value = PipelineBannerState(
                        isRunning = false,
                        statusBannerMessage = msg,
                        isError = true
                    )
                }
            )
            _isTestingGeminiConnection.value = false
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
