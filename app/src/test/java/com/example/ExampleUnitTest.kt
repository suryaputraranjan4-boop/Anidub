package com.example

import com.example.data.local.DictionaryEntryEntity
import com.example.engine.AudioDspEngine
import com.example.engine.HinglishTtsNormalizer
import com.example.engine.PcmAudioBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class ExampleUnitTest {

    @Test
    fun verifyGlobalDialogueNumberingAndCharacterWiseBatchOrder() {
        data class RawDialogue(val order: Int, val speaker: String)
        val originalChronological = listOf(
            RawDialogue(1, "A"),
            RawDialogue(2, "B"),
            RawDialogue(3, "C"),
            RawDialogue(4, "A"),
            RawDialogue(5, "B"),
            RawDialogue(6, "A"),
            RawDialogue(7, "C")
        )

        val numberedIds = originalChronological.map { "${it.speaker}-%03d".format(it.order) }
        assertEquals(
            listOf("A-001", "B-002", "C-003", "A-004", "B-005", "A-006", "C-007"),
            numberedIds
        )

        // Group into character-wise generation batches A -> B -> C sorted by global order
        val batches = originalChronological
            .groupBy { it.speaker }
            .toSortedMap()
            .mapValues { (_, list) ->
                list.sortedBy { it.order }.map { "${it.speaker}-%03d".format(it.order) }
            }

        assertEquals(listOf("A-001", "A-004", "A-006"), batches["A"])
        assertEquals(listOf("B-002", "B-005"), batches["B"])
        assertEquals(listOf("C-003", "C-007"), batches["C"])

        // Reconstruct original chronological timeline order
        val reconstructed = batches.values.flatten().sortedBy { it.substringAfter("-").toInt() }
        assertEquals(
            listOf("A-001", "B-002", "C-003", "A-004", "B-005", "A-006", "C-007"),
            reconstructed
        )
    }

    @Test
    fun verifyMixedScriptPronunciationNormalizerKeepsDisplayTextSeparateAndAppliesPronunciationOverride() {
        val dict = listOf(
            DictionaryEntryEntity(
                dictionaryLevel = "GLOBAL",
                japaneseSourceTerm = "ダンジョン",
                preferredMixedScript = "Dungeon",
                preferredRomanHinglish = "Dungeon",
                preferredHindiWording = "",
                preferredEnglishWording = "Dungeon",
                preserveOriginalEnglish = true,
                pronunciationGuidance = "dun-jun",
                contextNotes = "Keep in English"
            )
        )
        val visible = "मुझे इस Dungeon के अंदर जाना है, but अभी time नहीं है।"
        val normalized = HinglishTtsNormalizer.normalizeForTtsPronunciation(visible, dict)
        assertTrue("TTS text must never contain bracket tags", !normalized.contains("[EN:"))
        assertTrue("TTS text must apply dictionary pronunciation guidance", normalized.contains("dun-jun"))
        assertTrue("Display text itself must remain untouched Devanagari + English", visible.contains("Dungeon") && visible.contains("मुझे"))
    }

    @Test
    fun verifyAudioOnlyPitchAndSpeakerEmbeddingSimilarity() {
        val sr = 24000
        val samplesA1 = ShortArray(sr) { i ->
            (sin(2.0 * PI * 135.0 * (i.toDouble() / sr)) * 18000).toInt().toShort()
        }
        val samplesA2 = ShortArray(sr) { i ->
            (sin(2.0 * PI * 136.0 * (i.toDouble() / sr)) * 17500).toInt().toShort()
        }
        val feat1 = AudioDspEngine.analyzeVoiceSegment(PcmAudioBuffer(samplesA1, sr, 1))
        val feat2 = AudioDspEngine.analyzeVoiceSegment(PcmAudioBuffer(samplesA2, sr, 1))
        val sim = AudioDspEngine.computeCosineSimilarity(feat1.embedding, feat2.embedding)
        assertTrue("Expected high similarity for same speaker voice frequency, got $sim", sim > 0.90f)
    }

    @Test
    fun verifyAndroidOnlyModelCatalogHonestyAndExactSizes() {
        val catalog = com.example.engine.VerifiedModelCatalog.buildVerifiedModelCatalog()
        assertEquals("Expected 12 catalog entries (5 local + 1 Direct Gemini + 6 unsupported desktop PyTorch)", 12, catalog.size)

        for (model in catalog) {
            val files = com.example.engine.VerifiedModelCatalog.parseManifest(model.filesManifestJson)
            assertTrue(
                "Model ${model.modelId} must never download README.md",
                !model.downloadArtifactUrl.endsWith("README.md", ignoreCase = true)
            )
            for (file in files) {
                assertTrue(
                    "Artifact ${file.relativePath} in ${model.modelId} must never point to README.md",
                    !file.downloadUrl.endsWith("README.md", ignoreCase = true)
                )
            }

            val reserialized = com.example.engine.VerifiedModelCatalog.serializeManifest(files)
            val reparsed = com.example.engine.VerifiedModelCatalog.parseManifest(reserialized)
            assertEquals("Manifest round-trip mismatch for ${model.modelId}", files, reparsed)
        }

        // Only Silero VAD ONNX should be downloadable on Android
        val downloadable = catalog.filter { com.example.engine.VerifiedModelCatalog.isDownloadablePackage(it) }
        assertEquals(listOf("silero_vad_onnx"), downloadable.map { it.modelId })

        val silero = catalog.first { it.modelId == "silero_vad_onnx" }
        assertEquals(2_244_097L, silero.expectedSizeBytes)
        assertEquals(2, silero.totalFilesCount)
        assertEquals(com.example.engine.VerifiedModelCatalog.RUNTIME_ONNX_COMPATIBLE, silero.runtimeCompatibility)

        // Direct Gemini API is the sole online AI service
        val gemini = catalog.first { it.modelId == "direct_gemini_api" }
        assertEquals(com.example.engine.VerifiedModelCatalog.RUNTIME_DIRECT_GEMINI_API, gemini.runtimeCompatibility)

        // Whisper Large V3 Turbo and WhisperX Alignment Pipeline must be clearly distinguished & honest
        val whisper = catalog.first { it.modelId == "whisper_large_v3_turbo" }
        assertEquals(1_622_443_339L, whisper.expectedSizeBytes)
        assertEquals(com.example.engine.VerifiedModelCatalog.RUNTIME_UNSUPPORTED_ON_ANDROID, whisper.runtimeCompatibility)
        assertEquals("UNSUPPORTED_ON_ANDROID", whisper.downloadState)

        val whisperX = catalog.first { it.modelId == "whisperx_alignment_pipeline" }
        assertEquals(com.example.engine.VerifiedModelCatalog.RUNTIME_UNSUPPORTED_ON_ANDROID, whisperX.runtimeCompatibility)
        assertEquals("UNSUPPORTED_ON_ANDROID", whisperX.downloadState)

        // Chatterbox Multilingual V3 & F5-Hindi-24KHz must both be honestly UNSUPPORTED_ON_ANDROID
        val chatterbox = catalog.first { it.modelId == "chatterbox_multilingual_v3" }
        assertEquals(com.example.engine.VerifiedModelCatalog.RUNTIME_UNSUPPORTED_ON_ANDROID, chatterbox.runtimeCompatibility)
        assertEquals("UNSUPPORTED_ON_ANDROID", chatterbox.downloadState)

        val f5Hindi = catalog.first { it.modelId == "f5_tts_hindi_24khz" }
        assertEquals(632_168_396L, f5Hindi.expectedSizeBytes)
        assertEquals(com.example.engine.VerifiedModelCatalog.RUNTIME_UNSUPPORTED_ON_ANDROID, f5Hindi.runtimeCompatibility)
        assertEquals("UNSUPPORTED_ON_ANDROID", f5Hindi.downloadState)

        // Duplicate WeSpeaker entry must not exist
        assertTrue(catalog.none { it.modelId == "wespeaker_voxceleb_resnet34_lm" })
    }

    @Test
    fun verifySubtitleGeneratorAndValidatorEndToEnd() {
        val tempDir = java.nio.file.Files.createTempDirectory("sub_test").toFile()
        try {
            val dialogues = listOf(
                com.example.data.local.DialogueSegmentEntity(
                    id = 1L,
                    episodeId = 1L,
                    globalOrderNumber = 1,
                    dialogueId = "A-001",
                    speakerLabel = "A",
                    originalStartMs = 1000L,
                    originalEndMs = 3200L,
                    originalDurationMs = 2200L,
                    transcriptSegmentId = 1L,
                    japaneseText = "絶対に負けない！",
                    romanHinglishText = "मैं बिल्कुल नहीं हारूँगा!",
                    ttsNormalizedText = "मैं बिल्कुल नहीं हारूँगा!",
                    timelineStartMs = 1000L,
                    actualGeneratedDurationMs = 2150L
                ),
                com.example.data.local.DialogueSegmentEntity(
                    id = 2L,
                    episodeId = 1L,
                    globalOrderNumber = 2,
                    dialogueId = "B-002",
                    speakerLabel = "B",
                    originalStartMs = 3600L,
                    originalEndMs = 5600L,
                    originalDurationMs = 2000L,
                    transcriptSegmentId = 2L,
                    japaneseText = "面白いな。",
                    romanHinglishText = "यह काफी interesting है।",
                    ttsNormalizedText = "यह काफी interesting है।",
                    timelineStartMs = 3600L,
                    actualGeneratedDurationMs = 1900L
                )
            )
            val bundle = com.example.engine.SubtitleGenerator.generateAllSubtitles(
                episodeTitle = "Episode 1",
                dialogues = dialogues,
                speakersByLabel = emptyMap(),
                outputDir = tempDir,
                baseFileName = "ep01_test"
            )
            assertTrue("Expected all 4 subtitle files to pass validation: ${bundle.validationIssues}", bundle.validationPassed)
            assertEquals(2, bundle.cueCount)
            assertTrue(bundle.japaneseSrtFile.readText(Charsets.UTF_8).contains("絶対に負けない！"))
            assertTrue(bundle.hindiHinglishSrtFile.readText(Charsets.UTF_8).contains("मैं बिल्कुल नहीं हारूँगा!"))
            assertTrue(bundle.bilingualSrtFile.readText(Charsets.UTF_8).contains("यह काफी interesting है।"))
            assertTrue(bundle.styledAssFile.readText(Charsets.UTF_8).contains("Style: Speaker_A"))

            // Romanized Hindi in mixed-script SRT must fail strict mixed-script validation
            val badRomanSrt = java.io.File(tempDir, "roman_leak.srt")
            badRomanSrt.writeText("1\n00:00:01,000 --> 00:00:03,000\nMujhe tumhare saath jaana hai, lekin time nahi hai.\n", Charsets.UTF_8)
            val badRomanRes = com.example.engine.SubtitleGenerator.validateSubtitleFile(badRomanSrt, enforceMixedScript = true)
            assertEquals("Romanized Hindi SRT must fail mixed-script validation", false, badRomanRes.isValid)

            // Corrupt SRT file must fail validation honestly
            val badSrt = java.io.File(tempDir, "broken.srt")
            badSrt.writeText("1\n00:00:05,000 --> 00:00:02,000\nInvalid reversed timestamps\n", Charsets.UTF_8)
            val badRes = com.example.engine.SubtitleGenerator.validateSubtitleFile(badSrt)
            assertEquals("Reversed timestamp SRT must fail validation", false, badRes.isValid)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun verifyFinalVerificationAgentDetectsValidVsSilentAndMissingAudio() {
        val tempDir = java.nio.file.Files.createTempDirectory("verify_test").toFile()
        try {
            val sr = 24000
            // 1. Write a real non-silent 1-second WAV
            val activeSamples = ShortArray(sr) { i ->
                (sin(2.0 * PI * 220.0 * (i.toDouble() / sr)) * 12000).toInt().toShort()
            }
            val dlgWav = java.io.File(tempDir, "dlg_a_001.wav")
            AudioDspEngine.writeWavFile(dlgWav, PcmAudioBuffer(activeSamples, sr, 1))

            val masterWav = java.io.File(tempDir, "export_master.wav")
            AudioDspEngine.writeWavFile(masterWav, PcmAudioBuffer(activeSamples, sr, 1))

            val dlg = com.example.data.local.DialogueSegmentEntity(
                id = 1L,
                episodeId = 1L,
                globalOrderNumber = 1,
                dialogueId = "A-001",
                speakerLabel = "A",
                originalStartMs = 0L,
                originalEndMs = 1000L,
                originalDurationMs = 1000L,
                transcriptSegmentId = 1L,
                japaneseText = "行くぞ！",
                romanHinglishText = "चलो चलते हैं!",
                ttsNormalizedText = "चलो चलते हैं!",
                generatedAudioPath = dlgWav.absolutePath,
                actualGeneratedDurationMs = 1000L,
                timelineStartMs = 0L
            )
            val subBundle = com.example.engine.SubtitleGenerator.generateAllSubtitles(
                episodeTitle = "Ep 1",
                dialogues = listOf(dlg),
                speakersByLabel = emptyMap(),
                outputDir = tempDir,
                baseFileName = "ep1"
            )

            val okReport = com.example.engine.FinalVerificationAgent.verifyExportedOutput(
                outputFile = masterWav,
                exportFormat = "WAV_AUDIO_ONLY",
                expectedDurationMs = 1000L,
                dialogues = listOf(dlg),
                subtitleFiles = listOf(subBundle.hindiHinglishSrtFile, subBundle.styledAssFile)
            )
            assertTrue("Expected valid export verification to pass: ${okReport.summary}", okReport.passed)

            // 2. Write a completely silent WAV — must FAIL verification honestly!
            val silentWav = java.io.File(tempDir, "silent_master.wav")
            AudioDspEngine.writeWavFile(silentWav, PcmAudioBuffer(ShortArray(sr), sr, 1))
            val silentReport = com.example.engine.FinalVerificationAgent.verifyExportedOutput(
                outputFile = silentWav,
                exportFormat = "WAV_AUDIO_ONLY",
                expectedDurationMs = 1000L,
                dialogues = listOf(dlg),
                subtitleFiles = listOf(subBundle.hindiHinglishSrtFile)
            )
            assertEquals("Silent audio output must fail Final Verification", false, silentReport.passed)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun verifySpeakableTextPreparationHasNoBracketTagsAndAppliesDictionary() {
        val dict = listOf(
            DictionaryEntryEntity(
                dictionaryLevel = "PROJECT",
                projectId = 1L,
                japaneseSourceTerm = "領域展開",
                preferredMixedScript = "Domain Expansion",
                preferredRomanHinglish = "Domain Expansion",
                preferredHindiWording = "",
                preferredEnglishWording = "Domain Expansion",
                preserveOriginalEnglish = true,
                pronunciationGuidance = "Domain Expansion",
                contextNotes = "Preserve iconic attack name"
            )
        )
        val speakable = HinglishTtsNormalizer.prepareSpeakableText(
            "यह मेरी book है, Domain Expansion!",
            dict,
            projectId = 1L
        )
        assertTrue("Speakable text must not contain [EN: tags", !speakable.contains("[EN:"))
        assertTrue(speakable.contains("book"))
        assertTrue(speakable.contains("Domain Expansion"))
    }

    @Test
    fun verifyStrictWavParserRejectsCorruptFilesAndReadsValidWav() {
        val tempDir = java.nio.file.Files.createTempDirectory("wav_strict_test").toFile()
        try {
            val badFile = java.io.File(tempDir, "not_a_wav.wav")
            badFile.writeText("NOT_RIFF_DATA_AT_ALL_123456789012345678901234567890")
            val badRes = AudioDspEngine.readWavFileStrict(badFile)
            assertEquals("Corrupt non-RIFF file must fail strict WAV parsing", true, badRes.isFailure)

            val sr = 24000
            val sine = ShortArray(sr / 2) { i ->
                (sin(2.0 * PI * 300.0 * (i.toDouble() / sr)) * 10000).toInt().toShort()
            }
            val goodFile = java.io.File(tempDir, "valid.wav")
            AudioDspEngine.writeWavFile(goodFile, PcmAudioBuffer(sine, sr, 1))
            val okRes = AudioDspEngine.readWavFileStrict(goodFile)
            assertTrue("Valid WAV must parse cleanly", okRes.isSuccess)
            assertEquals(sr, okRes.getOrThrow().sampleRateHz)
            assertEquals(sine.size, okRes.getOrThrow().samples.size)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun verifyWsolaTimeStretchAndSilenceF0Unmeasured() {
        val sr = 24000
        val silent = PcmAudioBuffer(ShortArray(sr / 2), sr, 1)
        val silentFeat = AudioDspEngine.analyzeVoiceSegment(silent)
        assertEquals("Silent segment must report unmeasured F0 = 0f, never invented 185f", 0f, silentFeat.f0MeanHz, 0.001f)

        val tone = ShortArray(sr) { i ->
            (sin(2.0 * PI * 180.0 * (i.toDouble() / sr)) * 14000).toInt().toShort()
        }
        val (stretched, ratio) = AudioDspEngine.timeStretchPcm(PcmAudioBuffer(tone, sr, 1), targetDurationMs = 1250L)
        assertTrue("Stretched duration should be close to 1250ms, got ${stretched.durationMs}", kotlin.math.abs(stretched.durationMs - 1250L) <= 60L)
        assertTrue("Speed factor should reflect expansion (1000/1250 = 0.80x), got $ratio", ratio in 0.75f..0.85f)
        assertTrue("Stretched audio must remain non-silent", AudioDspEngine.computePeakDbfs(stretched) > -20f)
    }

    @Test
    fun verifyAdditiveTwoPassMultitrackMixerProducesNonClippedOutput() {
        val tempDir = java.nio.file.Files.createTempDirectory("mix_test").toFile()
        try {
            val sr = 24000
            val bg = ShortArray(sr * 2) { i ->
                (sin(2.0 * PI * 110.0 * (i.toDouble() / sr)) * 6000).toInt().toShort()
            }
            val clip1 = ShortArray(sr / 2) { i ->
                (sin(2.0 * PI * 440.0 * (i.toDouble() / sr)) * 20000).toInt().toShort()
            }
            val clip2 = ShortArray(sr / 2) { i ->
                (sin(2.0 * PI * 520.0 * (i.toDouble() / sr)) * 20000).toInt().toShort()
            }
            val bgFile = AudioDspEngine.writeWavFile(java.io.File(tempDir, "bg.wav"), PcmAudioBuffer(bg, sr, 1))
            val c1File = AudioDspEngine.writeWavFile(java.io.File(tempDir, "c1.wav"), PcmAudioBuffer(clip1, sr, 1))
            val c2File = AudioDspEngine.writeWavFile(java.io.File(tempDir, "c2.wav"), PcmAudioBuffer(clip2, sr, 1))
            val outFile = java.io.File(tempDir, "mixed.wav")

            val result = AudioDspEngine.mixMultitrackTimeline(
                episodeDurationMs = 2000L,
                sampleRateHz = sr,
                backgroundStemFile = bgFile,
                backgroundGain = 0.8f,
                dialoguePlacements = listOf(
                    com.example.engine.MixPlacementItem("A-001", "A", c1File, startMs = 200L, trackGain = 1.0f),
                    com.example.engine.MixPlacementItem("B-002", "B", c2File, startMs = 300L, trackGain = 1.0f)
                ),
                outputFile = outFile
            )
            assertTrue("Mixed WAV must exist and be non-empty", outFile.exists() && outFile.length() > 44L)
            assertTrue("Mixed peak must be safe (<= 0 dBFS)", result.peakDbfs <= 0.01f)
            assertTrue("Mixed audio must be audible", result.peakDbfs > -20f)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun verifySubtitleValidatorRejectsPlaceholdersAndOutOfBoundsCues() {
        val tempDir = java.nio.file.Files.createTempDirectory("sub_placeholder_test").toFile()
        try {
            val placeholderSrt = java.io.File(tempDir, "placeholder.srt")
            placeholderSrt.writeText("1\n00:00:01,000 --> 00:00:02,000\n[Unrecognized]\n")
            val res = com.example.engine.SubtitleGenerator.validateSubtitleFile(placeholderSrt, expectedCueCount = 1, episodeDurationMs = 5000L)
            assertEquals("Placeholder text must fail subtitle validation", false, res.isValid)

            val outOfBoundsSrt = java.io.File(tempDir, "oob.srt")
            outOfBoundsSrt.writeText("1\n00:00:10,000 --> 00:00:15,000\nReal spoken line\n")
            val oobRes = com.example.engine.SubtitleGenerator.validateSubtitleFile(outOfBoundsSrt, expectedCueCount = 1, episodeDurationMs = 5000L)
            assertEquals("Out-of-bounds cue must fail subtitle validation", false, oobRes.isValid)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun verifyDirectGeminiApiErrorClassificationAndModelNormalization() {
        assertEquals(
            "gemini-2.5-flash",
            com.example.data.remote.DirectGeminiApiClient.normalizeModelName("gemini-3.8-flash")
        )
        assertEquals(
            "gemini-2.5-flash",
            com.example.data.remote.DirectGeminiApiClient.normalizeModelName("")
        )
        assertEquals(
            "gemini-2.5-pro",
            com.example.data.remote.DirectGeminiApiClient.normalizeModelName("models/gemini-2.5-pro")
        )

        val invalidKey = com.example.data.remote.DirectGeminiApiClient.classifyHttpError(
            httpCode = 400,
            responseBody = """{"error":{"message":"API_KEY_INVALID: API key not valid."}}"""
        )
        assertEquals(com.example.data.remote.GeminiErrorKind.INVALID_API_KEY, invalidKey.kind)

        val unauthorized = com.example.data.remote.DirectGeminiApiClient.classifyHttpError(403, "PERMISSION_DENIED: invalid api key")
        assertEquals(com.example.data.remote.GeminiErrorKind.INVALID_API_KEY, unauthorized.kind)

        val missingModel = com.example.data.remote.DirectGeminiApiClient.classifyHttpError(404, "Model not found")
        assertEquals(com.example.data.remote.GeminiErrorKind.MODEL_NOT_FOUND, missingModel.kind)

        val rateLimited = com.example.data.remote.DirectGeminiApiClient.classifyHttpError(429, "Too many requests, please retry")
        assertEquals(com.example.data.remote.GeminiErrorKind.RATE_LIMITED, rateLimited.kind)

        val quotaBilling = com.example.data.remote.DirectGeminiApiClient.classifyHttpError(429, "You exceeded your current quota for billing project")
        assertEquals(com.example.data.remote.GeminiErrorKind.QUOTA_OR_BILLING, quotaBilling.kind)

        val serverErr = com.example.data.remote.DirectGeminiApiClient.classifyHttpError(503, "Service unavailable")
        assertEquals(com.example.data.remote.GeminiErrorKind.SERVER_ERROR, serverErr.kind)
    }

    @Test
    fun verifyMixedScriptLanguageRulesAndThreeTierDictionaryPrecedence() {
        // 1. Mixed-Script validation
        val validMixed = com.example.engine.MixedScriptLanguageEngine.validateMixedScript(
            "मुझे तुम्हारे साथ जाना है, but अभी time नहीं है।"
        )
        assertTrue("Natural Mixed-Script Devanagari + English must pass: ${validMixed.issueSummary}", validMixed.isValid)

        val validEnglish = com.example.engine.MixedScriptLanguageEngine.validateMixedScript(
            "I trusted you, Captain!"
        )
        assertTrue("Intentional pure English dialogue must pass: ${validEnglish.issueSummary}", validEnglish.isValid)

        val invalidRoman = com.example.engine.MixedScriptLanguageEngine.validateMixedScript(
            "Mujhe tumhare saath jaana hai, lekin abhi time nahi hai."
        )
        assertEquals("Romanized Hindi sentence must fail mixed-script validation", false, invalidRoman.isValid)

        // 2. 3-tier dictionary isolation & Project > Anime > Global precedence
        val allDicts = listOf(
            DictionaryEntryEntity(
                id = 1L,
                dictionaryLevel = "GLOBAL",
                japaneseSourceTerm = "先輩",
                preferredMixedScript = "सीनियर",
                preferredRomanHinglish = "सीनियर",
                preferredHindiWording = "सीनियर",
                preferredEnglishWording = "Senior",
                pronunciationGuidance = "Senior",
                contextNotes = ""
            ),
            DictionaryEntryEntity(
                id = 2L,
                dictionaryLevel = "ANIME",
                animeSeriesName = "Jujutsu Kaisen",
                japaneseSourceTerm = "先輩",
                preferredMixedScript = "Senpai",
                preferredRomanHinglish = "Senpai",
                preferredHindiWording = "",
                preferredEnglishWording = "Senpai",
                pronunciationGuidance = "Sen-pie",
                contextNotes = ""
            ),
            DictionaryEntryEntity(
                id = 3L,
                dictionaryLevel = "PROJECT",
                projectId = 10L,
                japaneseSourceTerm = "先輩",
                preferredMixedScript = "सेनपाई",
                preferredRomanHinglish = "सेनपाई",
                preferredHindiWording = "सेनपाई",
                preferredEnglishWording = "Senpai",
                pronunciationGuidance = "Sen-pai",
                contextNotes = ""
            ),
            DictionaryEntryEntity(
                id = 4L,
                dictionaryLevel = "PROJECT",
                projectId = 99L, // Different project — must NEVER leak into project 10
                japaneseSourceTerm = "領域展開",
                preferredMixedScript = "WRONG_PROJECT_TERM",
                preferredRomanHinglish = "WRONG_PROJECT_TERM",
                preferredHindiWording = "",
                preferredEnglishWording = "WRONG_PROJECT_TERM",
                pronunciationGuidance = "",
                contextNotes = ""
            )
        )

        val resolvedForProj10 = com.example.engine.MixedScriptLanguageEngine.resolveScopedDictionaries(
            allEntries = allDicts,
            projectId = 10L,
            animeSeriesName = "Jujutsu Kaisen"
        )
        assertEquals("Only Project 10 entry should win for 先輩 and Project 99 must not leak", 1, resolvedForProj10.size)
        assertEquals("सेनपाई", com.example.engine.MixedScriptLanguageEngine.preferredDisplayTerm(resolvedForProj10.first()))

        // 3. Longest-phrase boundary-aware replacement (zero partial-word corruption, zero bracket appending)
        val boundaryDict = listOf(
            DictionaryEntryEntity(
                id = 10L,
                dictionaryLevel = "GLOBAL",
                japaneseSourceTerm = "アート",
                preferredMixedScript = "Art",
                preferredRomanHinglish = "Art",
                preferredHindiWording = "art",
                preferredEnglishWording = "Art",
                pronunciationGuidance = "",
                contextNotes = ""
            )
        )
        val untouched = com.example.engine.MixedScriptLanguageEngine.applyDictionariesToDisplayText(
            displayText = "यह मेरा start point है, art नहीं।",
            japaneseSource = "",
            resolvedEntries = boundaryDict
        )
        assertTrue("Must not corrupt 'start' into 'stArt'", untouched.contains("start"))
        assertTrue("Must replace standalone 'art' with 'Art'", untouched.contains("Art"))
        assertTrue("Must never append parenthetical brackets", !untouched.contains("(Art)"))
    }

    @Test
    fun verifyAcousticPauseSplitterFindsRealSilenceValley() {
        val sr = 24000
        // 3 seconds total: 0..1.2s tone, 1.2..1.8s silence valley, 1.8..3.0s tone
        val samples = ShortArray(sr * 3) { i ->
            val tSec = i.toDouble() / sr
            if (tSec in 1.2..1.8) 0
            else (sin(2.0 * PI * 240.0 * tSec) * 14000).toInt().toShort()
        }
        val pcm = PcmAudioBuffer(samples, sr, 1)
        val splits = AudioDspEngine.splitSegmentByAcousticPauses(
            segmentPcm = pcm,
            segmentStartMs = 10_000L,
            segmentEndMs = 13_000L,
            sentenceWeights = listOf(20, 20)
        )
        assertEquals(2, splits.size)
        assertEquals(10_000L, splits[0].first)
        assertEquals(13_000L, splits[1].second)
        assertEquals("Sub-segments must be contiguous", splits[0].second, splits[1].first)
        assertTrue(
            "Boundary should fall inside the silence valley (11200..11800ms), got ${splits[0].second}",
            splits[0].second in 11_150L..11_850L
        )
    }

    @Test
    fun verifyDictionaryJsonExportImportRoundTripAndTtsCapabilityHonesty() {
        val entries = listOf(
            DictionaryEntryEntity(
                dictionaryLevel = "PROJECT",
                projectId = 7L,
                japaneseSourceTerm = "領域展開",
                preferredMixedScript = "Domain Expansion",
                preferredRomanHinglish = "Domain Expansion",
                preferredHindiWording = "",
                preferredEnglishWording = "Domain Expansion",
                preserveOriginalEnglish = true,
                pronunciationGuidance = "Domain Expansion",
                contextNotes = "Keep attack in English"
            ),
            DictionaryEntryEntity(
                dictionaryLevel = "GLOBAL",
                japaneseSourceTerm = "先輩",
                preferredMixedScript = "सीनियर",
                preferredRomanHinglish = "सीनियर",
                preferredHindiWording = "सीनियर",
                preferredEnglishWording = "Senior",
                preserveOriginalEnglish = false,
                pronunciationGuidance = "Senior",
                contextNotes = "Natural Hindi"
            )
        )
        val json = com.example.engine.MixedScriptLanguageEngine.exportDictionaryEntriesToJson(entries)
        val parsed = com.example.engine.MixedScriptLanguageEngine.parseDictionaryEntriesFromJson(
            json = json,
            fallbackProjectId = 7L,
            fallbackAnimeSeriesName = "Jujutsu Kaisen"
        ).getOrThrow()
        assertEquals(2, parsed.size)
        assertEquals("Domain Expansion", parsed[0].preferredMixedScript)
        assertEquals("PROJECT", parsed[0].dictionaryLevel)
        assertEquals(7L, parsed[0].projectId)
        assertEquals("सीनियर", parsed[1].preferredMixedScript)
        assertEquals("GLOBAL", parsed[1].dictionaryLevel)

        // Verify TTS engine capability honesty
        val androidTts = HinglishTtsNormalizer.AVAILABLE_TTS_ENGINES.first { it.engineId == "Android-Speech-TTS" }
        assertEquals(true, androidTts.isSupportedOnAndroid)
        assertEquals(false, androidTts.supportsVoiceCloning)
        assertEquals(false, androidTts.supportsReferenceAudio)

        val chatterbox = HinglishTtsNormalizer.AVAILABLE_TTS_ENGINES.first { it.engineId == "Chatterbox-Multilingual-V3" }
        assertEquals(false, chatterbox.isSupportedOnAndroid)

        val f5 = HinglishTtsNormalizer.AVAILABLE_TTS_ENGINES.first { it.engineId == "F5-Hindi-24KHz" }
        assertEquals(false, f5.isSupportedOnAndroid)
    }
}

