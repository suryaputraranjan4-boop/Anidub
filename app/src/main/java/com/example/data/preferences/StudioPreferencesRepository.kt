package com.example.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.data.remote.DirectGeminiApiClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.studioDataStore by preferencesDataStore(name = "anime_dub_studio_prefs")

/**
 * Android-only studio configuration (Direct Gemini API + On-Device Android processing).
 * External Python/GPU worker settings have been removed completely.
 */
data class StudioConfig(
    val onboardingCompleted: Boolean = false,
    val activeProjectId: Long = 1L,
    val activeEpisodeId: Long = 1L,
    val geminiModelName: String = DirectGeminiApiClient.DEFAULT_MODEL_ID,
    val selectedAsrVariant: String = "Silero VAD ONNX + Direct Gemini Audio",
    val selectedTtsEngine: String = "Android-Speech-TTS",
    val selectedSeparationModel: String = "On-Device Bandpass/Residual DSP",
    val defaultExportResolution: String = "Source Passthrough",
    val defaultVideoBitrateKbps: Int = 0,
    val defaultAudioCodec: String = "AAC-LC / PCM 16-bit",
    val defaultAudioBitrateKbps: Int = 320,
    val defaultAudioSampleRateHz: Int = 48000
)

class StudioPreferencesRepository(private val context: Context) {

    private object Keys {
        val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
        val ACTIVE_PROJECT_ID = longPreferencesKey("active_project_id")
        val ACTIVE_EPISODE_ID = longPreferencesKey("active_episode_id")
        val GEMINI_MODEL_NAME = stringPreferencesKey("gemini_model_name")
        val SELECTED_ASR = stringPreferencesKey("selected_asr")
        val SELECTED_TTS = stringPreferencesKey("selected_tts")
        val SELECTED_SEP = stringPreferencesKey("selected_sep")
        val EXPORT_RES = stringPreferencesKey("export_res")
        val VIDEO_BITRATE = intPreferencesKey("video_bitrate")
        val AUDIO_CODEC = stringPreferencesKey("audio_codec")
        val AUDIO_BITRATE = intPreferencesKey("audio_bitrate")
        val AUDIO_SR = intPreferencesKey("audio_sr")
    }

    val configFlow: Flow<StudioConfig> = context.studioDataStore.data.map { prefs: Preferences ->
        val storedModel = DirectGeminiApiClient.normalizeModelName(prefs[Keys.GEMINI_MODEL_NAME])
        val storedTts = prefs[Keys.SELECTED_TTS].orEmpty()
        val effectiveTts = if (storedTts.isBlank() || storedTts == "F5-Hindi-24KHz" || storedTts == "Chatterbox-Multilingual-V3") {
            "Android-Speech-TTS"
        } else {
            storedTts
        }
        val storedSep = prefs[Keys.SELECTED_SEP].orEmpty()
        val effectiveSep = if (storedSep.isBlank() || storedSep == "HTDemucs") {
            "On-Device Bandpass/Residual DSP"
        } else {
            storedSep
        }
        val storedAsr = prefs[Keys.SELECTED_ASR].orEmpty()
        val effectiveAsr = if (storedAsr.isBlank() || storedAsr.contains("Whisper", ignoreCase = true)) {
            "Silero VAD ONNX + Direct Gemini Audio"
        } else {
            storedAsr
        }

        StudioConfig(
            onboardingCompleted = prefs[Keys.ONBOARDING_DONE] ?: false,
            activeProjectId = prefs[Keys.ACTIVE_PROJECT_ID] ?: 1L,
            activeEpisodeId = prefs[Keys.ACTIVE_EPISODE_ID] ?: 1L,
            geminiModelName = storedModel,
            selectedAsrVariant = effectiveAsr,
            selectedTtsEngine = effectiveTts,
            selectedSeparationModel = effectiveSep,
            defaultExportResolution = prefs[Keys.EXPORT_RES] ?: "Source Passthrough",
            defaultVideoBitrateKbps = prefs[Keys.VIDEO_BITRATE] ?: 0,
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

    suspend fun updateGeminiModelName(modelName: String) {
        val clean = DirectGeminiApiClient.normalizeModelName(modelName)
        context.studioDataStore.edit {
            it[Keys.GEMINI_MODEL_NAME] = clean
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
