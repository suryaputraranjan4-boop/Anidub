package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.local.DialogueSegmentEntity
import com.example.data.local.DictionaryEntryEntity
import com.example.data.local.SpeakerProfileEntity
import com.example.engine.AudioPlaybackState
import com.example.ui.components.AudioPlayPreviewButton
import com.example.ui.components.AudioWaveformCanvas
import com.example.ui.components.StudioSectionHeader
import com.example.ui.components.formatTimecodeMs
import com.example.ui.theme.StudioAmber
import com.example.ui.theme.StudioAmberContainer
import com.example.ui.theme.StudioBorder
import com.example.ui.theme.StudioCrimson
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
import com.example.ui.theme.speakerColorFor

/**
 * Screen 12 — Agent 3: Speaker Library (100% Audio-Only Speaker Identification)
 */
@Composable
fun SpeakerLibraryScreen(
    speakers: List<SpeakerProfileEntity>,
    dialogues: List<DialogueSegmentEntity>,
    playbackState: AudioPlaybackState,
    onPlayAudio: (String, String) -> Unit,
    onStopAudio: () -> Unit,
    onRenameSpeaker: (SpeakerProfileEntity, String, Boolean) -> Unit,
    onReassignDialogueSpeaker: (DialogueSegmentEntity, String) -> Unit,
    onInspectVoiceAnalysis: (String) -> Unit,
    onOpenVoiceProfiles: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            StudioSectionHeader(
                title = "Agent 3 — Audio-Only Speaker Library",
                subtitle = "Stable speaker labels (A, B, C...) assigned by first chronological occurrence using 16-D voice embeddings & F0 acoustics",
                badgeText = "ZERO VISUAL RECOGNITION"
            )
            Spacer(modifier = Modifier.height(6.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = StudioEmeraldContainer),
                border = BorderStroke(1.dp, StudioEmerald),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.RecordVoiceOver, contentDescription = null, tint = StudioEmerald)
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Audio-First Guarantee: No visual character recognition, no facial recognition, and no video-frame matching are used.",
                        style = MaterialTheme.typography.bodySmall,
                        color = StudioTextPrimary
                    )
                }
            }
        }

        items(speakers) { sp ->
            val spColor = speakerColorFor(sp.speakerLabel)
            var editableName by remember(sp.id, sp.characterName) { mutableStateOf(sp.characterName) }

            Card(
                colors = CardDefaults.cardColors(containerColor = StudioSurface),
                border = BorderStroke(1.dp, spColor.copy(alpha = 0.6f)),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            color = spColor.copy(alpha = 0.2f),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, spColor)
                        ) {
                            Text(
                                text = "SPEAKER ${sp.speakerLabel}",
                                style = MaterialTheme.typography.labelLarge,
                                color = spColor,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                            )
                        }
                        Text(
                            text = "${sp.dialogueCount} Dialogues • Consistency ${(sp.voiceConsistencyScore * 100).toInt()}%",
                            style = MaterialTheme.typography.labelMedium,
                            color = StudioEmerald
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = editableName,
                            onValueChange = { editableName = it },
                            label = { Text("Character Name (${sp.speakerLabel} = ...)") },
                            modifier = Modifier.weight(1f).testTag("speaker_rename_${sp.speakerLabel}")
                        )
                        Button(
                            onClick = { onRenameSpeaker(sp, editableName, sp.rightsConsentConfirmed) },
                            colors = ButtonDefaults.buttonColors(containerColor = StudioViolet)
                        ) {
                            Icon(Icons.Default.Edit, contentDescription = "Save Name", modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Save")
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Pitch Range: ${sp.f0MinHz}–${sp.f0MaxHz} Hz (Mean ${sp.f0MeanHz} Hz)  •  Timbre: ${sp.timbreDescriptor}",
                        style = MaterialTheme.typography.bodySmall,
                        color = StudioTextSecondary
                    )
                    Text(
                        text = "Voice Reference ID: ${sp.voiceReferenceId}  •  Similarity: ${(sp.voiceSimilarityScore * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = StudioCyan
                    )

                    Spacer(modifier = Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AudioPlayPreviewButton(
                            filePath = sp.voiceReferencePath,
                            label = "Voice Ref ${sp.speakerLabel}",
                            playbackState = playbackState,
                            onPlayClick = onPlayAudio,
                            onStopClick = onStopAudio,
                            accentColor = spColor
                        )
                        OutlinedButton(onClick = { onInspectVoiceAnalysis(sp.speakerLabel) }) {
                            Text("Acoustic Analysis")
                        }
                        OutlinedButton(onClick = onOpenVoiceProfiles) {
                            Text("Profile")
                        }
                    }
                }
            }
        }

        // Manual correction of speaker assignments per dialogue
        item {
            StudioSectionHeader(
                title = "Manual Speaker Assignment Correction",
                subtitle = "Reassign any dialogue segment to Speaker A, B, or C while preserving global order number"
            )
        }

        items(dialogues) { dlg ->
            val spColor = speakerColorFor(dlg.speakerLabel)
            Card(
                colors = CardDefaults.cardColors(containerColor = StudioSurface),
                border = BorderStroke(1.dp, StudioBorder),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "${dlg.dialogueId} (${formatTimecodeMs(dlg.originalStartMs)} - ${formatTimecodeMs(dlg.originalEndMs)})",
                            style = MaterialTheme.typography.labelLarge,
                            color = spColor
                        )
                        Text(
                            text = dlg.japaneseText,
                            style = MaterialTheme.typography.bodySmall,
                            color = StudioTextPrimary,
                            maxLines = 1
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf("A", "B", "C").forEach { candidateLabel ->
                            FilterChip(
                                selected = dlg.speakerLabel == candidateLabel,
                                onClick = {
                                    if (dlg.speakerLabel != candidateLabel) {
                                        onReassignDialogueSpeaker(dlg, candidateLabel)
                                    }
                                },
                                label = { Text(candidateLabel) }
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Screen 13 — Character Voice Profiles & Consent
 */
@Composable
fun CharacterVoiceProfilesScreen(
    speakers: List<SpeakerProfileEntity>,
    playbackState: AudioPlaybackState,
    onPlayAudio: (String, String) -> Unit,
    onStopAudio: () -> Unit,
    onUpdateSpeakerConsent: (SpeakerProfileEntity, String, Boolean) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            StudioSectionHeader(
                title = "Character Voice Profiles & Cloning Consent",
                subtitle = "Reusable normalized voice reference per character with explicit rights/consent confirmation",
                badgeText = "REUSABLE VOICE REF"
            )
        }

        items(speakers) { sp ->
            val spColor = speakerColorFor(sp.speakerLabel)
            Card(
                colors = CardDefaults.cardColors(containerColor = StudioSurface),
                border = BorderStroke(1.dp, spColor.copy(alpha = 0.55f)),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Speaker ${sp.speakerLabel}: ${sp.characterName}",
                            style = MaterialTheme.typography.titleMedium,
                            color = StudioTextPrimary,
                            fontWeight = FontWeight.Bold
                        )
                        Surface(
                            color = StudioVioletContainer,
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = sp.voiceReferenceId,
                                style = MaterialTheme.typography.labelSmall,
                                color = StudioViolet,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Clean Non-Overlapping Reference Sample • ${sp.preferredTtsEngine}",
                        style = MaterialTheme.typography.bodySmall,
                        color = StudioCyan
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    AudioWaveformCanvas(
                        audioFilePath = sp.voiceReferencePath,
                        accentColor = spColor,
                        height = 48.dp
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    // Mandatory Voice Actor Rights / Consent Confirmation
                    Surface(
                        color = if (sp.rightsConsentConfirmed) StudioEmeraldContainer else StudioAmberContainer,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, if (sp.rightsConsentConfirmed) StudioEmerald else StudioAmber),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Default.Security,
                                        contentDescription = null,
                                        tint = if (sp.rightsConsentConfirmed) StudioEmerald else StudioAmber,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = if (sp.rightsConsentConfirmed) {
                                            "Voice Cloning Rights & Authorization Confirmed"
                                        } else {
                                            "Using Consented Generic Studio Voice Alternative"
                                        },
                                        style = MaterialTheme.typography.titleSmall,
                                        color = StudioTextPrimary
                                    )
                                }
                                Text(
                                    text = "Toggle OFF if authorization is unavailable to automatically switch to a consented generic studio voice profile.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = StudioTextSecondary
                                )
                            }
                            Switch(
                                checked = sp.rightsConsentConfirmed,
                                onCheckedChange = { confirmed ->
                                    onUpdateSpeakerConsent(sp, sp.characterName, confirmed)
                                }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    AudioPlayPreviewButton(
                        filePath = sp.voiceReferencePath,
                        label = "Preview Reference (${sp.voiceReferenceId})",
                        playbackState = playbackState,
                        onPlayClick = onPlayAudio,
                        onStopClick = onStopAudio,
                        accentColor = spColor
                    )
                }
            }
        }
    }
}

/**
 * Screen 14 — Voice Analysis Details (Agent 3 Acoustic & Embedding Inspector)
 */
@Composable
fun VoiceAnalysisDetailsScreen(
    speakers: List<SpeakerProfileEntity>,
    selectedSpeakerLabel: String,
    onSelectSpeaker: (String) -> Unit
) {
    val activeSpeaker = speakers.find { it.speakerLabel == selectedSpeakerLabel }
        ?: speakers.firstOrNull()

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            StudioSectionHeader(
                title = "Agent 3 — Acoustic & Voice Embedding Analysis",
                subtitle = "11 multi-dimensional voice characteristics extracted strictly from audio (Zero visual matching)",
                badgeText = "LIBROSA.PYIN + 16-D EMBEDDING"
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                speakers.forEach { sp ->
                    FilterChip(
                        selected = sp.speakerLabel == activeSpeaker?.speakerLabel,
                        onClick = { onSelectSpeaker(sp.speakerLabel) },
                        label = { Text("Speaker ${sp.speakerLabel} (${sp.characterName.take(12)})") }
                    )
                }
            }
        }

        if (activeSpeaker != null) {
            val spColor = speakerColorFor(activeSpeaker.speakerLabel)
            val acousticMetrics = listOf(
                "1. Pitch & F0 Range" to "${activeSpeaker.f0MinHz} Hz – ${activeSpeaker.f0MaxHz} Hz",
                "2. Mean F0 & Pitch Variation (Std)" to "Mean: ${activeSpeaker.f0MeanHz} Hz  •  Std Dev: ±${activeSpeaker.f0StdHz} Hz",
                "3. Tone Characteristics" to "Harmonic Fundamental with Balanced Formant Tilt",
                "4. Timbre Classification" to activeSpeaker.timbreDescriptor,
                "5. Spectral Centroid" to "${activeSpeaker.spectralCentroidHz} Hz",
                "6. Speaking Speed" to "${activeSpeaker.speakingSpeedSylPerSec} syllables / sec",
                "7. Voice Texture" to activeSpeaker.voiceTexture,
                "8. Speaking Style" to activeSpeaker.speakingStyle,
                "9. Dominant Emotion Estimate" to activeSpeaker.dominantEmotion,
                "10. Intra-Cluster Voice Similarity" to "${(activeSpeaker.voiceSimilarityScore * 100).toInt()}% Cosine Match",
                "11. Episode Voice Consistency" to "${(activeSpeaker.voiceConsistencyScore * 100).toInt()}% Cross-Dialogue Stability"
            )

            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = StudioSurface),
                    border = BorderStroke(1.dp, spColor),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = "Speaker ${activeSpeaker.speakerLabel} — ${activeSpeaker.characterName}",
                            style = MaterialTheme.typography.headlineSmall,
                            color = spColor
                        )
                        acousticMetrics.forEach { (label, value) ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(text = label, style = MaterialTheme.typography.bodySmall, color = StudioTextSecondary, modifier = Modifier.weight(0.46f))
                                Text(text = value, style = MaterialTheme.typography.labelMedium, color = StudioTextPrimary, modifier = Modifier.weight(0.54f))
                            }
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "16-Dimensional Acoustic Speaker Embedding Vector:",
                            style = MaterialTheme.typography.labelMedium,
                            color = StudioCyan
                        )
                        Text(
                            text = activeSpeaker.speakerEmbeddingJson,
                            style = MaterialTheme.typography.labelSmall,
                            color = StudioTextSecondary
                        )
                    }
                }
            }
        }
    }
}

/**
 * Screen 15 — Agent 4: Translation Studio (Firebase AI Logic Gemini 3.8 Flash + Scoped Dictionary)
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TranslationStudioScreen(
    dialogues: List<DialogueSegmentEntity>,
    onReRunGeminiTranslation: () -> Unit,
    onUpdateSingleLineAndRegenerate: (Long, String, String) -> Unit,
    onApplyScopedTermReplacement: (
        targetDialogueId: Long?,
        japaneseTerm: String,
        oldWord: String,
        newRomanHinglish: String,
        pronunciation: String,
        scope: String
    ) -> Unit,
    onOpenGlobalDictionary: () -> Unit,
    onOpenAnimeDictionary: () -> Unit
) {
    var selectedDlgForTermEdit by remember { mutableStateOf<DialogueSegmentEntity?>(null) }
    var oldTermInput by remember { mutableStateOf("Dungeon") }
    var newTermInput by remember { mutableStateOf("Dungeon") }
    var jpTermInput by remember { mutableStateOf("ダンジョン") }
    var pronInput by remember { mutableStateOf("[EN:Dungeon]") }
    var selectedScope by remember { mutableStateOf("EVERYWHERE") }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            StudioSectionHeader(
                title = "Agent 4 — Gemini Roman Hinglish Translation Studio",
                subtitle = "Visible translation strictly uses natural spoken Roman Hinglish (never Devanagari) with preserved Japanese source & internal TTS phonetic representation",
                badgeText = "GEMINI 3.8 FLASH"
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onReRunGeminiTranslation,
                    colors = ButtonDefaults.buttonColors(containerColor = StudioViolet),
                    modifier = Modifier.weight(1f).testTag("translation_run_gemini_btn")
                ) {
                    Icon(Icons.Default.Translate, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Translate All with Gemini")
                }
                OutlinedButton(onClick = onOpenGlobalDictionary) {
                    Text("Global Dict")
                }
                OutlinedButton(onClick = onOpenAnimeDictionary) {
                    Text("Anime Dict")
                }
            }
        }

        // Scoped Dictionary Term Replacement Workbench (All 6 Mandatory Scopes)
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = StudioSurface),
                border = BorderStroke(1.dp, StudioCyan.copy(alpha = 0.6f)),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "SCOPED DICTIONARY REPLACEMENT & DEPENDENCY-AWARE INVALIDATION",
                        style = MaterialTheme.typography.labelMedium,
                        color = StudioCyan
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = oldTermInput,
                            onValueChange = { oldTermInput = it },
                            label = { Text("Find Term") },
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = newTermInput,
                            onValueChange = { newTermInput = it },
                            label = { Text("Preferred Roman Hinglish") },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = jpTermInput,
                            onValueChange = { jpTermInput = it },
                            label = { Text("Japanese Source Term") },
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = pronInput,
                            onValueChange = { pronInput = it },
                            label = { Text("TTS Pronunciation") },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Text(
                        text = "Select Replacement Scope (Target Dialogue: ${selectedDlgForTermEdit?.dialogueId ?: dialogues.firstOrNull()?.dialogueId ?: "A-001"}):",
                        style = MaterialTheme.typography.labelSmall,
                        color = StudioTextSecondary
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(
                            "EVERYWHERE" to "Change everywhere",
                            "ONLY_HERE" to "Change only here",
                            "CURRENT_DIALOGUE" to "Change in current dialogue",
                            "CURRENT_EPISODE" to "Change in current episode",
                            "CURRENT_ANIME" to "Change in current anime",
                            "GLOBALLY" to "Change globally"
                        ).forEach { (scopeKey, label) ->
                            FilterChip(
                                selected = selectedScope == scopeKey,
                                onClick = { selectedScope = scopeKey },
                                label = { Text(label) }
                            )
                        }
                    }
                    Button(
                        onClick = {
                            val targetId = selectedDlgForTermEdit?.id ?: dialogues.firstOrNull()?.id
                            onApplyScopedTermReplacement(
                                targetId,
                                jpTermInput,
                                oldTermInput,
                                newTermInput,
                                pronInput,
                                selectedScope
                            )
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = StudioCyan),
                        modifier = Modifier.fillMaxWidth().testTag("apply_scoped_dict_btn")
                    ) {
                        Text("Apply Scoped Dictionary Replacement & Regenerate Affected Audio Only", color = StudioSurface)
                    }
                }
            }
        }

        items(dialogues) { dlg ->
            val spColor = speakerColorFor(dlg.speakerLabel)
            var editHinglish by remember(dlg.id, dlg.romanHinglishText) { mutableStateOf(dlg.romanHinglishText) }
            var editEmotion by remember(dlg.id, dlg.emotion) { mutableStateOf(dlg.emotion) }

            Card(
                colors = CardDefaults.cardColors(containerColor = StudioSurface),
                border = BorderStroke(1.dp, spColor.copy(alpha = 0.5f)),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { selectedDlgForTermEdit = dlg }
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${dlg.dialogueId} • Speaker ${dlg.speakerLabel} (${formatTimecodeMs(dlg.originalStartMs)} - ${formatTimecodeMs(dlg.originalEndMs)})",
                            style = MaterialTheme.typography.labelLarge,
                            color = spColor,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Window: %.2fs".format(dlg.originalDurationMs / 1000.0),
                            style = MaterialTheme.typography.labelMedium,
                            color = StudioEmerald
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Original Japanese (Preserved): ${dlg.japaneseText}",
                        style = MaterialTheme.typography.bodySmall,
                        color = StudioTextSecondary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = editHinglish,
                        onValueChange = { editHinglish = it },
                        label = { Text("Visible Roman Hinglish Translation (Never Devanagari)") },
                        modifier = Modifier.fillMaxWidth().testTag("hinglish_input_${dlg.dialogueId}")
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Internal TTS Pronunciation Layer: ${dlg.ttsNormalizedText}",
                        style = MaterialTheme.typography.labelSmall,
                        color = StudioCyan
                    )

                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Emotion: $editEmotion • Style: ${dlg.speakingStyle}",
                            style = MaterialTheme.typography.labelSmall,
                            color = StudioTextMuted
                        )
                        Button(
                            onClick = { onUpdateSingleLineAndRegenerate(dlg.id, editHinglish, editEmotion) },
                            colors = ButtonDefaults.buttonColors(containerColor = StudioViolet)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Save & Regen ${dlg.dialogueId}")
                        }
                    }
                }
            }
        }
    }
}

/**
 * Screen 16 & 17 — Global Dictionary & Anime/Project Dictionary Screens
 */
@Composable
fun DictionaryManagerScreen(
    levelFilter: String, // "GLOBAL" or "ANIME_PROJECT"
    entries: List<DictionaryEntryEntity>,
    onSaveEntry: (DictionaryEntryEntity) -> Unit,
    onDeleteEntry: (Long) -> Unit,
    onSwitchLevelView: () -> Unit
) {
    var selectedLevel by remember(levelFilter) {
        mutableStateOf(if (levelFilter == "GLOBAL") "GLOBAL" else "ANIME")
    }
    var jpSource by remember { mutableStateOf("") }
    var romanHinglish by remember { mutableStateOf("") }
    var hindiWord by remember { mutableStateOf("") }
    var englishWord by remember { mutableStateOf("") }
    var pronunciation by remember { mutableStateOf("") }
    var contextNote by remember { mutableStateOf("") }
    var preserveOrig by remember { mutableStateOf(true) }

    val filteredEntries = entries.filter {
        if (levelFilter == "GLOBAL") it.dictionaryLevel == "GLOBAL"
        else it.dictionaryLevel == "ANIME" || it.dictionaryLevel == "PROJECT"
    }

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
                    title = if (levelFilter == "GLOBAL") "Global Dubbing Dictionary" else "Anime & Project Specific Dictionary",
                    subtitle = "Checked by Gemini before every translation. Approved terms persist across future episodes.",
                    badgeText = "${filteredEntries.size} TERMS"
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            OutlinedButton(onClick = onSwitchLevelView, modifier = Modifier.fillMaxWidth()) {
                Text(if (levelFilter == "GLOBAL") "Switch to Anime & Project Dictionary" else "Switch to Global Dictionary")
            }
        }

        // Add new dictionary term card
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = StudioSurface),
                border = BorderStroke(1.dp, StudioViolet),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Add Persistent Dictionary Entry", style = MaterialTheme.typography.titleSmall, color = StudioTextPrimary)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("GLOBAL", "ANIME", "PROJECT").forEach { lvl ->
                            FilterChip(
                                selected = selectedLevel == lvl,
                                onClick = { selectedLevel = lvl },
                                label = { Text("$lvl Level") }
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = jpSource,
                            onValueChange = { jpSource = it },
                            label = { Text("Japanese Term (e.g. ギルド)") },
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = romanHinglish,
                            onValueChange = { romanHinglish = it },
                            label = { Text("Preferred Roman Hinglish") },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = hindiWord,
                            onValueChange = { hindiWord = it },
                            label = { Text("Hindi Wording") },
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = englishWord,
                            onValueChange = { englishWord = it },
                            label = { Text("English Wording") },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = pronunciation,
                            onValueChange = { pronunciation = it },
                            label = { Text("Pronunciation Guidance") },
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = contextNote,
                            onValueChange = { contextNote = it },
                            label = { Text("Context / Scope Note") },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Button(
                        onClick = {
                            if (jpSource.isNotBlank() && romanHinglish.isNotBlank()) {
                                onSaveEntry(
                                    DictionaryEntryEntity(
                                        dictionaryLevel = selectedLevel,
                                        projectId = if (selectedLevel == "PROJECT") 1L else null,
                                        animeSeriesName = if (selectedLevel != "GLOBAL") "Chronicles of the Dungeon King" else null,
                                        japaneseSourceTerm = jpSource.trim(),
                                        preferredRomanHinglish = romanHinglish.trim(),
                                        preferredHindiWording = hindiWord.trim().ifBlank { romanHinglish.trim() },
                                        preferredEnglishWording = englishWord.trim().ifBlank { romanHinglish.trim() },
                                        preserveOriginalEnglish = preserveOrig,
                                        pronunciationGuidance = pronunciation.trim().ifBlank { romanHinglish.trim() },
                                        contextNotes = contextNote.trim().ifBlank { "User-defined dictionary rule" },
                                        replacementScope = selectedLevel
                                    )
                                )
                                jpSource = ""
                                romanHinglish = ""
                                hindiWord = ""
                                englishWord = ""
                                pronunciation = ""
                                contextNote = ""
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = StudioViolet),
                        modifier = Modifier.fillMaxWidth().testTag("dict_add_term_btn")
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Save Term to $selectedLevel Dictionary")
                    }
                }
            }
        }

        items(filteredEntries) { entry ->
            Card(
                colors = CardDefaults.cardColors(containerColor = StudioSurface),
                border = BorderStroke(1.dp, StudioBorder),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                color = StudioCyanContainer,
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    text = entry.dictionaryLevel,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = StudioCyan,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "${entry.japaneseSourceTerm}  →  \"${entry.preferredRomanHinglish}\"",
                                style = MaterialTheme.typography.titleMedium,
                                color = StudioTextPrimary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Pronunciation: ${entry.pronunciationGuidance}  •  Hindi: ${entry.preferredHindiWording}  •  English: ${entry.preferredEnglishWording}",
                            style = MaterialTheme.typography.labelSmall,
                            color = StudioEmerald
                        )
                        Text(
                            text = "Context: ${entry.contextNotes}",
                            style = MaterialTheme.typography.bodySmall,
                            color = StudioTextSecondary
                        )
                    }
                    IconButton(onClick = { onDeleteEntry(entry.id) }) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete term", tint = StudioCrimson)
                    }
                }
            }
        }
    }
}
