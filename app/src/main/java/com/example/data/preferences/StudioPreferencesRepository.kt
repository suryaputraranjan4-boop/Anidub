package com.example.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.studioDataStore by preferencesDataStore(name = "anime_dub_studio_prefs")

data class WorkerAndStudioConfig(
    val onboardingCompleted: Boolean = false,
    val activeProjectId: Long = 1L,
    val activeEpisodeId: Long = 1L,
    val remoteWorkerEnabled: Boolean = false,
    val remoteWorkerBaseUrl: String = "",
    val remoteWorkerHasAuthToken: Boolean = false,
    val workerGpuAvailable: Boolean = false,
    val maxProcessingConcurrency: Int = 2,
    val requestTimeoutSeconds: Int = 120,
    val maxUploadLimitMb: Int = 2048,
    val selectedAsrVariant: String = "Faster-Whisper Large-v3 (Quality)",
    val selectedTtsEngine: String = "XTTS-v2",
    val selectedSeparationModel: String = "UVR-MDX-Net-Voc_FT",
    val defaultExportResolution: String = "1920x1080 (1080p Full HD)",
    val defaultVideoBitrateKbps: Int = 8500,
    val defaultAudioCodec: String = "AAC-LC / PCM 16-bit",
    val defaultAudioBitrateKbps: Int = 320,
    val defaultAudioSampleRateHz: Int = 48000
)

class StudioPreferencesRepository(private val context: Context) {

    private object Keys {
        val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
        val ACTIVE_PROJECT_ID = longPreferencesKey("active_project_id")
        val ACTIVE_EPISODE_ID = longPreferencesKey("active_episode_id")
        val WORKER_ENABLED = booleanPreferencesKey("worker_enabled")
        val WORKER_URL = stringPreferencesKey("worker_url")
        val WORKER_HAS_TOKEN = booleanPreferencesKey("worker_has_token")
        val WORKER_GPU = booleanPreferencesKey("worker_gpu")
        val MAX_CONCURRENCY = intPreferencesKey("max_concurrency")
        val TIMEOUT_SEC = intPreferencesKey("timeout_sec")
        val UPLOAD_LIMIT_MB = intPreferencesKey("upload_limit_mb")
        val SELECTED_ASR = stringPreferencesKey("selected_asr")
        val SELECTED_TTS = stringPreferencesKey("selected_tts")
        val SELECTED_SEP = stringPreferencesKey("selected_sep")
        val EXPORT_RES = stringPreferencesKey("export_res")
        val VIDEO_BITRATE = intPreferencesKey("video_bitrate")
        val AUDIO_CODEC = stringPreferencesKey("audio_codec")
        val AUDIO_BITRATE = intPreferencesKey("audio_bitrate")
        val AUDIO_SR = intPreferencesKey("audio_sr")
    }

    val configFlow: Flow<WorkerAndStudioConfig> = context.studioDataStore.data.map { prefs: Preferences ->
        WorkerAndStudioConfig(
            onboardingCompleted = prefs[Keys.ONBOARDING_DONE] ?: false,
            activeProjectId = prefs[Keys.ACTIVE_PROJECT_ID] ?: 1L,
            activeEpisodeId = prefs[Keys.ACTIVE_EPISODE_ID] ?: 1L,
            remoteWorkerEnabled = prefs[Keys.WORKER_ENABLED] ?: false,
            remoteWorkerBaseUrl = prefs[Keys.WORKER_URL] ?: "",
            remoteWorkerHasAuthToken = prefs[Keys.WORKER_HAS_TOKEN] ?: false,
            workerGpuAvailable = prefs[Keys.WORKER_GPU] ?: false,
            maxProcessingConcurrency = prefs[Keys.MAX_CONCURRENCY] ?: 2,
            requestTimeoutSeconds = prefs[Keys.TIMEOUT_SEC] ?: 120,
            maxUploadLimitMb = prefs[Keys.UPLOAD_LIMIT_MB] ?: 2048,
            selectedAsrVariant = prefs[Keys.SELECTED_ASR] ?: "Faster-Whisper Large-v3 (Quality)",
            selectedTtsEngine = prefs[Keys.SELECTED_TTS] ?: "XTTS-v2",
            selectedSeparationModel = prefs[Keys.SELECTED_SEP] ?: "UVR-MDX-Net-Voc_FT",
            defaultExportResolution = prefs[Keys.EXPORT_RES] ?: "1920x1080 (1080p Full HD)",
            defaultVideoBitrateKbps = prefs[Keys.VIDEO_BITRATE] ?: 8500,
            defaultAudioCodec = prefs[Keys.AUDIO_CODEC] ?: "AAC-LC / PCM 16-bit",
            defaultAudioBitrateKbps = prefs[Keys.AUDIO_BITRATE] ?: 320,
            defaultAudioSampleRateHz = prefs[Keys.AUDIO_SR] ?: 48000
        )
    }

    suspend fun setOnboardingCompleted(completed: Boolean) {
        context.studioDataStore.edit { it[Keys.ONBOARDING_DONE] = completed }
    }

    suspend fun setActiveProjectAndEpisode(projectId: Long, episodeId: Long) {
        context.studioDataStore.edit {
            it[Keys.ACTIVE_PROJECT_ID] = projectId
            it[Keys.ACTIVE_EPISODE_ID] = episodeId
        }
    }

    suspend fun updateWorkerSettings(
        enabled: Boolean,
        url: String,
        hasAuthToken: Boolean,
        gpuAvailable: Boolean,
        maxConcurrency: Int,
        timeoutSeconds: Int,
        uploadLimitMb: Int
    ) {
        context.studioDataStore.edit {
            it[Keys.WORKER_ENABLED] = enabled
            it[Keys.WORKER_URL] = url.trim()
            it[Keys.WORKER_HAS_TOKEN] = hasAuthToken
            it[Keys.WORKER_GPU] = gpuAvailable
            it[Keys.MAX_CONCURRENCY] = maxConcurrency.coerceIn(1, 16)
            it[Keys.TIMEOUT_SEC] = timeoutSeconds.coerceIn(15, 600)
            it[Keys.UPLOAD_LIMIT_MB] = uploadLimitMb.coerceIn(100, 10240)
        }
    }

    suspend fun updateModelSelections(
        asrVariant: String,
        ttsEngine: String,
        separationModel: String
    ) {
        context.studioDataStore.edit {
            it[Keys.SELECTED_ASR] = asrVariant
            it[Keys.SELECTED_TTS] = ttsEngine
            it[Keys.SELECTED_SEP] = separationModel
        }
    }

    suspend fun updateDefaultExportSettings(
        resolution: String,
        videoBitrateKbps: Int,
        audioCodec: String,
        audioBitrateKbps: Int,
        audioSampleRateHz: Int
    ) {
        context.studioDataStore.edit {
            it[Keys.EXPORT_RES] = resolution
            it[Keys.VIDEO_BITRATE] = videoBitrateKbps
            it[Keys.AUDIO_CODEC] = audioCodec
            it[Keys.AUDIO_BITRATE] = audioBitrateKbps
            it[Keys.AUDIO_SR] = audioSampleRateHz
        }
    }
}
