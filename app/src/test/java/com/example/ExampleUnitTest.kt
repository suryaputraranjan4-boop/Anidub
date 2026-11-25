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
    fun verifyRomanHinglishPronunciationNormalizerPreservesVisibleTextAndEnglishTerms() {
        val dict = listOf(
            DictionaryEntryEntity(
                dictionaryLevel = "GLOBAL",
                japaneseSourceTerm = "ダンジョン",
                preferredRomanHinglish = "Dungeon",
                preferredHindiWording = "Tehkhana",
                preferredEnglishWording = "Dungeon",
                preserveOriginalEnglish = true,
                pronunciationGuidance = "[EN:Dungeon]",
                contextNotes = "Keep in English"
            )
        )
        val visible = "Mujhe kam mat samajhna. Ye meri book hai."
        val normalized = HinglishTtsNormalizer.normalizeForTtsPronunciation(visible, dict)
        assertTrue(normalized.contains("mu-jhay"))
        assertTrue(normalized.contains("[EN:book].") || normalized.contains("[EN:book]"))
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
}
