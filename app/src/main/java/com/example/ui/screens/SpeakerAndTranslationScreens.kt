package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.data.local.DialogueSegmentEntity
import com.example.data.local.DictionaryEntryEntity
import com.example.data.local.SpeakerProfileEntity
import com.example.ui.components.AudioPlayPauseButton
import com.example.ui.components.AudioWaveformCanvas
import com.example.ui.components.MetadataChipRow
import com.example.ui.components.StatusPill
import com.example.ui.components.StudioCard
import com.example.ui.components.StudioEmptyState
import com.example.ui.components.StudioSectionHeader
import com.example.ui.components.WarningBanner
import com.example.ui.components.speakerAccentColor
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.StudioAmber
import com.example.ui.theme.StudioBorder
import com.example.ui.theme.StudioBorderSubtle
import com.example.ui.theme.StudioCyan
import com.example.ui.theme.StudioEmerald
import com.example.ui.theme.StudioSurface
import com.example.ui.theme.StudioSurfaceElevated
import com.example.ui.theme.StudioTextMuted
import com.example.ui.theme.StudioTextPrimary
import com.example.ui.theme.StudioTextSecondary
import com.example.ui.theme.StudioViolet
import com.example.viewmodel.StudioViewModel

/**
 * SPEAKER LIBRARY & CHARACTER PROFILES (AUDIO-ONLY)
 * Zero visual or facial recognition. Neutral default names ("Speaker A", "Speaker B"),
 * editable character names, measured F0 / timbre / speaking style / emotion,
 * explicit Source Voice Consent vs Authorized Generic Voice distinction, and reference clip playback.
 */
@Composable
fun SpeakerProfilesView(viewModel: StudioViewModel) {
    val speakers by viewModel.speakers.collectAsState()
    val playback by viewModel.playbackState.collectAsState()
    val banner by viewModel.pipelineBanner.collectAsState()

    var editingSpeaker by remember { mutableStateOf<SpeakerProfileEntity?>(null) }

    if (editingSpeaker != null) {
        val sp = editingSpeaker!!
        var nameInput by remember(sp.id) { mutableStateOf(sp.characterName) }
        var consentConfirmed by remember(sp.id) { mutableStateOf(sp.rightsConsentConfirmed) }

        AlertDialog(
            onDismissRequest = { editingSpeaker = null },
            containerColor = StudioSurfaceElevated,
            title = { Text("Edit Speaker ${sp.speakerLabel}", color = StudioTextPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = nameInput,
                        onValueChange = { nameInput = it },
                        label = { Text("Character Name") },
                        placeholder = { Text("Speaker ${sp.speakerLabel}") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Source Voice Rights Confirmed",
                                style = MaterialTheme.typography.labelLarge,
                                color = StudioTextPrimary
                            )
                            Text(
                                text = if (consentConfirmed) {
                                    "Cloning from source vocal reference permitted."
                                } else {
                                    "Unconfirmed: uses authorized generic studio voice fallback."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = StudioTextSecondary
                            )
                        }
                        Switch(
                            checked = consentConfirmed,
                            onCheckedChange = { consentConfirmed = it },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color(0xFF0A0B0F),
                                checkedTrackColor = StudioEmerald
                            )
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.renameSpeakerCharacter(sp, nameInput, consentConfirmed)
                        editingSpeaker = null
                    }
                ) {
                    Text("Save", color = StudioViolet)
                }
            },
            dismissButton = {
                TextButton(onClick = { editingSpeaker = null }) {
                    Text("Cancel", color = StudioTextSecondary)
                }
            }
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("speaker_profiles_list"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            StudioSectionHeader(
                title = "Speaker Profiles (Audio-Only)",
                subtitle = "Clustered strictly from F0 pitch & 16-D vocal embeddings — zero facial/video recognition"
            )
        }

        if (speakers.isEmpty()) {
            item {
                StudioEmptyState(
                    icon = Icons.Default.RecordVoiceOver,
                    title = "No Detected Speakers Yet",
                    description = "Run Stage 6 Audio-Only Speaker Analysis after speech segmentation to cluster recurring voices and build reusable character profiles.",
                    primaryActionLabel = "Run Speaker Analysis (Stage 6)",
                    onPrimaryAction = { if (!banner.isRunning) viewModel.runPipelineStages(6, 7) },
                    testTagPrefix = "speakers_empty"
                )
            }
        } else {
            items(speakers, key = { it.id }) { sp ->
                val accent = speakerAccentColor(sp.speakerLabel)
                val consentLabel = if (sp.rightsConsentConfirmed) "Source Voice (Consented)" else "Authorized Generic Fallback"
                val consentColor = if (sp.rightsConsentConfirmed) StudioEmerald else StudioAmber

                StudioCard(padding = 16.dp) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            StatusPill(text = "Speaker ${sp.speakerLabel}", color = accent)
                            Column {
                                Text(
                                    text = sp.characterName,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = StudioTextPrimary
                                )
                                Text(
                                    text = "${sp.dialogueCount} dialogue line(s) • ${sp.voiceReferenceId}",
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                                    color = StudioTextMuted
                                )
                            }
                        }
                        IconButton(
                            onClick = { editingSpeaker = sp },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(Icons.Default.Edit, contentDescription = "Edit ${sp.characterName}", tint = StudioTextSecondary)
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Explicit Rights Consent vs Authorized Generic Voice Badge + Inline Toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(Icons.Default.VerifiedUser, contentDescription = null, tint = consentColor, modifier = Modifier.size(16.dp))
                            StatusPill(text = consentLabel, color = consentColor)
                        }
                        Switch(
                            checked = sp.rightsConsentConfirmed,
                            onCheckedChange = { confirmed ->
                                viewModel.renameSpeakerCharacter(sp, sp.characterName, confirmed)
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color(0xFF0A0B0F),
                                checkedTrackColor = StudioEmerald
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    MetadataChipRow(
                        items = listOf(
                            "F0 Mean" to if (sp.f0MeanHz > 0f) "${sp.f0MeanHz} Hz" else "—",
                            "Pitch Range" to if (sp.f0MaxHz > 0f) "${sp.f0MinHz.toInt()}–${sp.f0MaxHz.toInt()} Hz" else "—",
                            "Speed" to if (sp.speakingSpeedSylPerSec > 0f) "${sp.speakingSpeedSylPerSec} syl/s" else "—"
                        )
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "${sp.timbreDescriptor} • Style: ${sp.speakingStyle} • Dominant Tone: ${sp.dominantEmotion}",
                        style = MaterialTheme.typography.bodySmall,
                        color = StudioTextSecondary
                    )

                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Voice Reference Sample", style = MaterialTheme.typography.labelSmall, color = StudioTextMuted)
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        AudioPlayPauseButton(
                            filePath = sp.voiceReferencePath,
                            label = "${sp.characterName} Reference",
                            playbackState = playback,
                            onTogglePlay = { path, lbl -> viewModel.audioPlayer.togglePlayPause(path, lbl) },
                            accentColor = accent
                        )
                        AudioWaveformCanvas(
                            audioFilePath = sp.voiceReferencePath,
                            accentColor = accent,
                            modifier = Modifier.weight(1f),
                            barCount = 48
                        )
                    }
                }
            }
        }
    }
}

/**
 * TRANSLATION & DICTIONARY STUDIO
 * Clean Roman Hinglish translation editor (Japanese -> Roman Hinglish, never Devanagari)
 * paired with Global / Anime / Project dictionary management.
 */
@Composable
fun TranslationAndDictionaryView(viewModel: StudioViewModel) {
    val dialogues by viewModel.dialoguesChronological.collectAsState()
    val dictionaries by viewModel.dictionaries.collectAsState()
    val activeProject by viewModel.activeProject.collectAsState()
    val banner by viewModel.pipelineBanner.collectAsState()

    var subMode by remember { mutableStateOf("TRANSLATIONS") } // TRANSLATIONS or DICTIONARIES
    var dictLevelFilter by remember { mutableStateOf("GLOBAL") } // GLOBAL, ANIME, PROJECT
    var showAddDictDialog by remember { mutableStateOf(false) }
    var editingDialogue by remember { mutableStateOf<DialogueSegmentEntity?>(null) }

    val emotions = listOf("NEUTRAL", "ANGRY", "EXCITED", "CALM", "SAD", "HAPPY", "FEARFUL", "SHOUTING", "WHISPERING")

    if (editingDialogue != null) {
        val dlg = editingDialogue!!
        var hinglishText by remember(dlg.id) { mutableStateOf(dlg.romanHinglishText) }
        var emotion by remember(dlg.id) { mutableStateOf(dlg.emotion) }
        var style by remember(dlg.id) { mutableStateOf(dlg.speakingStyle) }
        val hasDevanagari = Regex("[\\u0900-\\u097F]").containsMatchIn(hinglishText)

        AlertDialog(
            onDismissRequest = { editingDialogue = null },
            containerColor = StudioSurfaceElevated,
            title = { Text("Translate ${dlg.dialogueId}", color = StudioTextPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (dlg.japaneseText.isNotBlank()) {
                        Text(dlg.japaneseText, style = MaterialTheme.typography.bodySmall, color = StudioTextSecondary)
                    }
                    OutlinedTextField(
                        value = hinglishText,
                        onValueChange = { hinglishText = it },
                        label = { Text("Roman Hinglish (Latin Script Only)") },
                        modifier = Modifier.fillMaxWidth(),
                        isError = hasDevanagari,
                        minLines = 2
                    )
                    if (hasDevanagari) {
                        Text(
                            "Devanagari script detected. Use Roman Hinglish (e.g., 'Mujhe kam mat samajhna.').",
                            style = MaterialTheme.typography.labelSmall,
                            color = StudioAmber
                        )
                    }
                    OutlinedTextField(
                        value = style,
                        onValueChange = { style = it },
                        label = { Text("Speaking Style") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Text("Emotion", style = MaterialTheme.typography.labelSmall, color = StudioTextSecondary)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        emotions.take(4).forEach { em ->
                            FilterChip(
                                selected = emotion == em,
                                onClick = { emotion = em },
                                label = { Text(em, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        emotions.drop(4).take(4).forEach { em ->
                            FilterChip(
                                selected = emotion == em,
                                onClick = { emotion = em },
                                label = { Text(em, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(
                        enabled = !hasDevanagari,
                        onClick = {
                            viewModel.updateDialogueTranslationOnly(dlg, hinglishText, emotion, style)
                            editingDialogue = null
                        }
                    ) {
                        Text("Save Text", color = StudioCyan)
                    }
                    TextButton(
                        enabled = !hasDevanagari && hinglishText.isNotBlank(),
                        onClick = {
                            viewModel.updateAndRegenerateSingleDialogue(dlg.id, hinglishText, emotion)
                            editingDialogue = null
                        }
                    ) {
                        Text("Save & Voice", color = StudioViolet)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { editingDialogue = null }) {
                    Text("Cancel", color = StudioTextSecondary)
                }
            }
        )
    }

    if (showAddDictDialog) {
        var jpTerm by remember { mutableStateOf("") }
        var romanTerm by remember { mutableStateOf("") }
        var pronGuide by remember { mutableStateOf("") }
        var notes by remember { mutableStateOf("") }
        var level by remember { mutableStateOf(dictLevelFilter) }

        AlertDialog(
            onDismissRequest = { showAddDictDialog = false },
            containerColor = StudioSurfaceElevated,
            title = { Text("Add Dictionary Term", color = StudioTextPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("GLOBAL", "ANIME", "PROJECT").forEach { lvl ->
                            FilterChip(
                                selected = level == lvl,
                                onClick = { level = lvl },
                                label = { Text(lvl, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }
                    OutlinedTextField(
                        value = jpTerm,
                        onValueChange = { jpTerm = it },
                        label = { Text("Japanese Term") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = romanTerm,
                        onValueChange = { romanTerm = it },
                        label = { Text("Preferred Roman Hinglish / English") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = pronGuide,
                        onValueChange = { pronGuide = it },
                        label = { Text("Pronunciation Hint (Optional)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = notes,
                        onValueChange = { notes = it },
                        label = { Text("Context Notes") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = jpTerm.isNotBlank() && romanTerm.isNotBlank(),
                    onClick = {
                        viewModel.addOrUpdateDictionaryEntry(
                            DictionaryEntryEntity(
                                dictionaryLevel = level,
                                projectId = if (level == "PROJECT") activeProject?.id else null,
                                animeSeriesName = if (level == "ANIME") activeProject?.animeSeriesName else null,
                                japaneseSourceTerm = jpTerm.trim(),
                                preferredRomanHinglish = romanTerm.trim(),
                                preferredHindiWording = romanTerm.trim(),
                                preferredEnglishWording = romanTerm.trim(),
                                preserveOriginalEnglish = true,
                                pronunciationGuidance = pronGuide.trim().ifBlank { romanTerm.trim() },
                                contextNotes = notes.trim()
                            )
                        )
                        showAddDictDialog = false
                    }
                ) {
                    Text("Save Term", color = StudioViolet)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDictDialog = false }) {
                    Text("Cancel", color = StudioTextSecondary)
                }
            }
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("translation_studio_list"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Mode Switcher: Translations vs Dictionaries
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = subMode == "TRANSLATIONS",
                    onClick = { subMode = "TRANSLATIONS" },
                    label = { Text("Hinglish Editor (${dialogues.size})") },
                    modifier = Modifier.weight(1f),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = StudioViolet.copy(alpha = 0.2f),
                        selectedLabelColor = StudioViolet,
                        containerColor = StudioSurface
                    )
                )
                FilterChip(
                    selected = subMode == "DICTIONARIES",
                    onClick = { subMode = "DICTIONARIES" },
                    label = { Text("Dictionaries (${dictionaries.size})") },
                    modifier = Modifier.weight(1f),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = StudioCyan.copy(alpha = 0.2f),
                        selectedLabelColor = StudioCyan,
                        containerColor = StudioSurface
                    )
                )
            }
        }

        if (subMode == "TRANSLATIONS") {
            item {
                StudioCard(elevated = true) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Roman Hinglish Translation", style = MaterialTheme.typography.titleMedium, color = StudioTextPrimary)
                            Text(
                                "Powered by Gemini 3.8 Flash via Firebase AI Logic with 3-tier dictionary enforcement.",
                                style = MaterialTheme.typography.bodySmall,
                                color = StudioTextSecondary
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = { viewModel.runPipelineStages(8, 8) },
                        enabled = !banner.isRunning && dialogues.any { it.japaneseText.isNotBlank() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("translation_run_gemini_btn"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = StudioViolet,
                            contentColor = Color(0xFF0A0B0F)
                        ),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Translate All Japanese Lines with Gemini")
                    }
                }
            }

            if (dialogues.isEmpty()) {
                item {
                    StudioEmptyState(
                        icon = Icons.Default.Translate,
                        title = "No Dialogues Ready for Translation",
                        description = "Segment and transcribe dialogue lines in the Transcript tab first before translating into Roman Hinglish.",
                        testTagPrefix = "translation_empty"
                    )
                }
            } else {
                items(dialogues, key = { it.id }) { dlg ->
                    val accent = speakerAccentColor(dlg.speakerLabel)
                    StudioCard(padding = 14.dp) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                StatusPill(text = dlg.dialogueId, color = accent)
                                StatusPill(text = dlg.emotion, color = StudioCyan)
                                Text(
                                    text = "${"%.2f".format(dlg.originalDurationMs / 1000.0)}s",
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                                    color = StudioTextMuted
                                )
                            }
                            IconButton(
                                onClick = { editingDialogue = dlg },
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(Icons.Default.Edit, contentDescription = "Edit translation", tint = StudioViolet, modifier = Modifier.size(18.dp))
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                            val wide = maxWidth >= 520.dp
                            if (wide) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("JAPANESE SOURCE", style = MaterialTheme.typography.labelSmall, color = StudioTextMuted)
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = dlg.japaneseText.ifBlank { "No Japanese text entered" },
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = if (dlg.japaneseText.isNotBlank()) StudioTextSecondary else StudioAmber
                                        )
                                    }
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("ROMAN HINGLISH", style = MaterialTheme.typography.labelSmall, color = StudioViolet)
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = dlg.romanHinglishText.ifBlank { "Not translated yet" },
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = if (dlg.romanHinglishText.isNotBlank()) StudioTextPrimary else StudioTextMuted
                                        )
                                    }
                                }
                            } else {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Column {
                                        Text("JAPANESE SOURCE", style = MaterialTheme.typography.labelSmall, color = StudioTextMuted)
                                        Text(
                                            text = dlg.japaneseText.ifBlank { "No Japanese text entered" },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = if (dlg.japaneseText.isNotBlank()) StudioTextSecondary else StudioAmber
                                        )
                                    }
                                    HorizontalDivider(color = StudioBorderSubtle)
                                    Column {
                                        Text("ROMAN HINGLISH", style = MaterialTheme.typography.labelSmall, color = StudioViolet)
                                        Text(
                                            text = dlg.romanHinglishText.ifBlank { "Not translated yet — tap Edit or run Gemini." },
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = if (dlg.romanHinglishText.isNotBlank()) StudioTextPrimary else StudioTextMuted
                                        )
                                    }
                                }
                            }
                        }

                        if (dlg.ttsNormalizedText.isNotBlank()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "TTS Phonetic: ${dlg.ttsNormalizedText}",
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                                color = StudioTextMuted
                            )
                        }
                    }
                }
            }
        } else {
            // 3-Tier Dictionaries: Global, Anime, Project
            item {
                StudioSectionHeader(
                    title = "Terminology Dictionaries",
                    subtitle = "Priority: Project > Anime > Global",
                    trailing = {
                        Button(
                            onClick = { showAddDictDialog = true },
                            modifier = Modifier.heightIn(min = 48.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = StudioViolet,
                                contentColor = Color(0xFF0A0B0F)
                            ),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Add Term")
                        }
                    }
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("GLOBAL", "ANIME", "PROJECT").forEach { lvl ->
                        FilterChip(
                            selected = dictLevelFilter == lvl,
                            onClick = { dictLevelFilter = lvl },
                            label = { Text(lvl) }
                        )
                    }
                }
            }

            val scopedDicts = dictionaries.filter { it.dictionaryLevel == dictLevelFilter }
            if (scopedDicts.isEmpty()) {
                item {
                    StudioEmptyState(
                        icon = Icons.Default.Book,
                        title = "No $dictLevelFilter Dictionary Terms",
                        description = "Add recurring character names, anime attacks, or English loanwords so Gemini translation and TTS pronunciation stay consistent.",
                        primaryActionLabel = "Add $dictLevelFilter Term",
                        onPrimaryAction = { showAddDictDialog = true }
                    )
                }
            } else {
                items(scopedDicts, key = { it.id }) { entry ->
                    StudioCard(padding = 12.dp) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "${entry.japaneseSourceTerm}  →  ${entry.preferredRomanHinglish}",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = StudioTextPrimary
                                )
                                Text(
                                    text = "Pronunciation: ${entry.pronunciationGuidance.ifBlank { entry.preferredRomanHinglish }}${if (entry.contextNotes.isNotBlank()) " • ${entry.contextNotes}" else ""}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = StudioTextSecondary
                                )
                            }
                            IconButton(
                                onClick = { viewModel.deleteDictionaryEntry(entry.id) },
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(Icons.Default.DeleteOutline, contentDescription = "Delete term", tint = StudioTextMuted)
                            }
                        }
                    }
                }
            }
        }
    }
}
