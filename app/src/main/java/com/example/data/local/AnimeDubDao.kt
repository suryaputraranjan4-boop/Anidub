package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface AnimeDubDao {

    // Projects
    @Query("SELECT * FROM projects ORDER BY updatedAt DESC")
    fun observeProjects(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects ORDER BY updatedAt DESC")
    suspend fun getProjectsOnce(): List<ProjectEntity>

    @Query("SELECT * FROM projects WHERE id = :projectId LIMIT 1")
    suspend fun getProjectById(projectId: Long): ProjectEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProject(project: ProjectEntity): Long

    @Update
    suspend fun updateProject(project: ProjectEntity)

    // Episodes
    @Query("SELECT * FROM episodes WHERE projectId = :projectId ORDER BY episodeNumber ASC")
    fun observeEpisodesForProject(projectId: Long): Flow<List<EpisodeEntity>>

    @Query("SELECT * FROM episodes ORDER BY updatedAt DESC")
    fun observeAllEpisodes(): Flow<List<EpisodeEntity>>

    @Query("SELECT * FROM episodes WHERE id = :episodeId LIMIT 1")
    fun observeEpisodeById(episodeId: Long): Flow<EpisodeEntity?>

    @Query("SELECT * FROM episodes WHERE id = :episodeId LIMIT 1")
    suspend fun getEpisodeById(episodeId: Long): EpisodeEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEpisode(episode: EpisodeEntity): Long

    @Update
    suspend fun updateEpisode(episode: EpisodeEntity)

    // Audio Stems (Agent 6)
    @Query("SELECT * FROM audio_stems WHERE episodeId = :episodeId ORDER BY id ASC")
    fun observeStemsForEpisode(episodeId: Long): Flow<List<AudioStemEntity>>

    @Query("SELECT * FROM audio_stems WHERE episodeId = :episodeId ORDER BY id ASC")
    suspend fun getStemsForEpisodeOnce(episodeId: Long): List<AudioStemEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStem(stem: AudioStemEntity): Long

    @Update
    suspend fun updateStem(stem: AudioStemEntity)

    @Query("DELETE FROM audio_stems WHERE episodeId = :episodeId AND stemType != 'ORIGINAL_MASTER'")
    suspend fun deleteSeparatedStemsKeepMaster(episodeId: Long)

    // Japanese Transcripts (Agent 1)
    @Query("SELECT * FROM transcript_segments WHERE episodeId = :episodeId ORDER BY segmentIndex ASC")
    fun observeTranscriptsForEpisode(episodeId: Long): Flow<List<TranscriptSegmentEntity>>

    @Query("SELECT * FROM transcript_segments WHERE episodeId = :episodeId ORDER BY segmentIndex ASC")
    suspend fun getTranscriptsForEpisodeOnce(episodeId: Long): List<TranscriptSegmentEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTranscriptSegments(segments: List<TranscriptSegmentEntity>)

    @Update
    suspend fun updateTranscriptSegment(segment: TranscriptSegmentEntity)

    @Query("DELETE FROM transcript_segments WHERE episodeId = :episodeId")
    suspend fun clearTranscriptsForEpisode(episodeId: Long)

    // Dialogue Segments (Agent 2, 3, 4, 5, 7)
    @Query("SELECT * FROM dialogue_segments WHERE episodeId = :episodeId ORDER BY globalOrderNumber ASC")
    fun observeDialoguesChronological(episodeId: Long): Flow<List<DialogueSegmentEntity>>

    @Query("SELECT * FROM dialogue_segments WHERE episodeId = :episodeId ORDER BY globalOrderNumber ASC")
    suspend fun getDialoguesChronologicalOnce(episodeId: Long): List<DialogueSegmentEntity>

    // Character-wise batch query: sorts by speakerLabel (A, B, C...) then globalOrderNumber ASC!
    @Query("SELECT * FROM dialogue_segments WHERE episodeId = :episodeId ORDER BY speakerLabel ASC, globalOrderNumber ASC")
    fun observeDialoguesCharacterWiseBatches(episodeId: Long): Flow<List<DialogueSegmentEntity>>

    @Query("SELECT * FROM dialogue_segments WHERE episodeId = :episodeId ORDER BY speakerLabel ASC, globalOrderNumber ASC")
    suspend fun getDialoguesCharacterWiseBatchesOnce(episodeId: Long): List<DialogueSegmentEntity>

    @Query("SELECT * FROM dialogue_segments WHERE id = :id LIMIT 1")
    suspend fun getDialogueById(id: Long): DialogueSegmentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDialogues(dialogues: List<DialogueSegmentEntity>)

    @Update
    suspend fun updateDialogue(dialogue: DialogueSegmentEntity)

    @Query("DELETE FROM dialogue_segments WHERE episodeId = :episodeId")
    suspend fun clearDialoguesForEpisode(episodeId: Long)

    // Speaker Profiles (Agent 3 & Agent 5)
    @Query("SELECT * FROM speaker_profiles WHERE episodeId = :episodeId ORDER BY speakerLabel ASC")
    fun observeSpeakersForEpisode(episodeId: Long): Flow<List<SpeakerProfileEntity>>

    @Query("SELECT * FROM speaker_profiles WHERE episodeId = :episodeId ORDER BY speakerLabel ASC")
    suspend fun getSpeakersForEpisodeOnce(episodeId: Long): List<SpeakerProfileEntity>

    @Query("SELECT * FROM speaker_profiles WHERE projectId = :projectId ORDER BY speakerLabel ASC")
    suspend fun getSpeakersForProjectOnce(projectId: Long): List<SpeakerProfileEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSpeakerProfiles(profiles: List<SpeakerProfileEntity>)

    @Update
    suspend fun updateSpeakerProfile(profile: SpeakerProfileEntity)

    @Query("DELETE FROM speaker_profiles WHERE episodeId = :episodeId")
    suspend fun clearSpeakersForEpisode(episodeId: Long)

    // Dictionary (Agent 4: Global, Anime, Project levels)
    @Query("SELECT * FROM dictionary_entries ORDER BY dictionaryLevel ASC, japaneseSourceTerm ASC")
    fun observeAllDictionaryEntries(): Flow<List<DictionaryEntryEntity>>

    @Query("SELECT * FROM dictionary_entries ORDER BY dictionaryLevel ASC, japaneseSourceTerm ASC")
    suspend fun getAllDictionaryEntriesOnce(): List<DictionaryEntryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDictionaryEntry(entry: DictionaryEntryEntity): Long

    @Update
    suspend fun updateDictionaryEntry(entry: DictionaryEntryEntity)

    @Query("DELETE FROM dictionary_entries WHERE id = :id")
    suspend fun deleteDictionaryEntry(id: Long)

    // Voice Generation Records (Agent 5)
    @Query("SELECT * FROM voice_generation_records WHERE episodeId = :episodeId ORDER BY characterBatchNumber ASC, orderInCharacterBatch ASC, createdAt DESC")
    fun observeVoiceGenerationRecords(episodeId: Long): Flow<List<VoiceGenerationRecordEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertVoiceGenerationRecord(record: VoiceGenerationRecordEntity): Long

    // Timeline Tracks (Agent 7)
    @Query("SELECT * FROM timeline_tracks WHERE episodeId = :episodeId ORDER BY trackIndex ASC")
    fun observeTimelineTracks(episodeId: Long): Flow<List<TimelineTrackEntity>>

    @Query("SELECT * FROM timeline_tracks WHERE episodeId = :episodeId ORDER BY trackIndex ASC")
    suspend fun getTimelineTracksOnce(episodeId: Long): List<TimelineTrackEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTimelineTracks(tracks: List<TimelineTrackEntity>)

    @Update
    suspend fun updateTimelineTrack(track: TimelineTrackEntity)

    @Query("DELETE FROM timeline_tracks WHERE episodeId = :episodeId")
    suspend fun clearTimelineTracks(episodeId: Long)

    // Quality Control Results (Stage 13)
    @Query("SELECT * FROM quality_check_results WHERE episodeId = :episodeId ORDER BY checkNumber ASC")
    fun observeQualityChecks(episodeId: Long): Flow<List<QualityCheckResultEntity>>

    @Query("SELECT * FROM quality_check_results WHERE episodeId = :episodeId ORDER BY checkNumber ASC")
    suspend fun getQualityChecksOnce(episodeId: Long): List<QualityCheckResultEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertQualityChecks(checks: List<QualityCheckResultEntity>)

    @Query("DELETE FROM quality_check_results WHERE episodeId = :episodeId")
    suspend fun clearQualityChecks(episodeId: Long)

    // Model Manifests (Section 8)
    @Query("SELECT * FROM model_manifests ORDER BY specialistAgentNumber ASC, modelName ASC")
    fun observeAllModels(): Flow<List<ModelManifestEntity>>

    @Query("SELECT * FROM model_manifests ORDER BY specialistAgentNumber ASC, modelName ASC")
    suspend fun getAllModelsOnce(): List<ModelManifestEntity>

    @Query("SELECT * FROM model_manifests WHERE modelId = :modelId LIMIT 1")
    suspend fun getModelById(modelId: String): ModelManifestEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertModels(models: List<ModelManifestEntity>)

    @Update
    suspend fun updateModel(model: ModelManifestEntity)

    // Agent Job Records (Processing Center & History)
    @Query("SELECT * FROM agent_job_records WHERE episodeId = :episodeId ORDER BY stageNumber ASC, id DESC")
    fun observeAgentJobsForEpisode(episodeId: Long): Flow<List<AgentJobRecordEntity>>

    @Query("SELECT * FROM agent_job_records ORDER BY updatedAt DESC LIMIT 100")
    fun observeAllAgentJobHistory(): Flow<List<AgentJobRecordEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAgentJob(job: AgentJobRecordEntity): Long

    @Update
    suspend fun updateAgentJob(job: AgentJobRecordEntity)

    @Query("DELETE FROM agent_job_records WHERE episodeId = :episodeId")
    suspend fun clearAgentJobsForEpisode(episodeId: Long)

    // Export Records (Stage 14)
    @Query("SELECT * FROM export_records WHERE episodeId = :episodeId ORDER BY exportedAt DESC")
    fun observeExportsForEpisode(episodeId: Long): Flow<List<ExportRecordEntity>>

    @Query("SELECT * FROM export_records ORDER BY exportedAt DESC LIMIT 20")
    fun observeAllExports(): Flow<List<ExportRecordEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExportRecord(record: ExportRecordEntity): Long

    @Query("DELETE FROM projects WHERE id = :projectId")
    suspend fun deleteProjectById(projectId: Long)
}
