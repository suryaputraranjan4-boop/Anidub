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
                subtitle = "Remote pyannote/WeSpeaker diarization or non-neural acoustic F0/spectral fallback — zero facial recognition"
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
                    val simLabel = if (sp.voiceSimilarityScore >= 0f) "${(sp.voiceSimilarityScore * 100).toInt()}%" else "Unmeasured"
                    MetadataChipRow(
                        items = listOf(
                            "F0 Mean" to if (sp.f0MeanHz > 0f) "${sp.f0MeanHz} Hz" else "Unmeasured",
                            "Pitch Range" to if (sp.f0MaxHz > 0f) "${sp.f0MinHz.toInt()}–${sp.f0MaxHz.toInt()} Hz" else "Unmeasured",
                            "Similarity" to simLabel
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
 * Permanent Mixed-Script Hindi-English (Devanagari + English) translation editor with:
 * - Live mixed-script compliance validation + one-tap "Fix Script with Gemini" repair
 * - Timing source transparency (`ASR-Aligned` / `Acoustic-Estimated` / `Manual`) & split parent ref
 * - Separate read-only TTS pronunciation feed preview
 * - Genuinely editable 3-Tier Dictionaries (`Project > Anime > Global`) with strict project/anime isolation,
 *   live search, Add / Edit / Delete CRUD, and full Devanagari / English / Mixed-Script / Pronunciation fields.
 */
@Composable
fun TranslationAndDictionaryView(viewModel: StudioViewModel) {
    val dialogues by viewModel.dialoguesChronological.collectAsState()
    val dictionaries by viewModel.dictionaries.collectAsState()
    val activeProject by viewModel.activeProject.collectAsState()
    val banner by viewModel.pipelineBanner.collectAsState()
    val config by viewModel.studioConfig.collectAsState()

    var subMode by remember { mutableStateOf("TRANSLATIONS") } // TRANSLATIONS or DICTIONARIES
    var dictLevelFilter by remember { mutableStateOf("ALL_ACTIVE") } // ALL_ACTIVE, PROJECT, ANIME, GLOBAL
    var dictSearchQuery by remember { mutableStateOf("") }
    var editingDictEntry by remember { mutableStateOf<DictionaryEntryEntity?>(null) }
    var showDictDialog by remember { mutableStateOf(false) }
    var editingDialogue by remember { mutableStateOf<DialogueSegmentEntity?>(null) }

    val emotions = listOf("NEUTRAL", "ANGRY", "EXCITED", "CALM", "SAD", "HAPPY", "FEARFUL", "SHOUTING", "WHISPERING")

    // Strictly scoped active dictionaries for the current project/anime (zero cross-project leakage)
    val activeScopedDicts = remember(dictionaries, activeProject) {
        com.example.engine.MixedScriptLanguageEngine.resolveScopedDictionaries(
            allEntries = dictionaries,
            projectId = activeProject?.id,
            animeSeriesName = activeProject?.animeSeriesName
        )
    }

    if (editingDialogue != null) {
        val dlg = editingDialogue!!
        var mixedText by remember(dlg.id) { mutableStateOf(dlg.romanHinglishText) }
        var emotion by remember(dlg.id) { mutableStateOf(dlg.emotion) }
        var style by remember(dlg.id) { mutableStateOf(dlg.speakingStyle) }
        var isFixingScript by remember(dlg.id) { mutableStateOf(false) }

        val scriptCheck = remember(mixedText) {
            com.example.engine.MixedScriptLanguageEngine.validateMixedScript(mixedText)
        }
        val showRomanWarning = mixedText.isNotBlank() && !scriptCheck.isValid

        AlertDialog(
            onDismissRequest = { if (!isFixingScript) editingDialogue = null },
            containerColor = StudioSurfaceElevated,
            title = { Text("Translate ${dlg.dialogueId}", color = StudioTextPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (dlg.japaneseText.isNotBlank()) {
                        Text(dlg.japaneseText, style = MaterialTheme.typography.bodySmall, color = StudioTextSecondary)
                    }
                    OutlinedTextField(
                        value = mixedText,
                        onValueChange = { mixedText = it },
                        label = { Text("Mixed-Script Hindi-English (देवनागरी + English)") },
                        placeholder = { Text("उदाहरण: मुझे तुम्हारे साथ जाना है, but अभी time नहीं है।") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("dialogue_mixed_script_input"),
                        isError = showRomanWarning,
                        minLines = 2
                    )
                    if (showRomanWarning) {
                        Text(
                            text = scriptCheck.issueSummary.ifBlank {
                                "Romanized Hindi detected. Write Hindi words in Devanagari and keep English words in English."
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = StudioAmber
                        )
                        OutlinedButton(
                            onClick = {
                                isFixingScript = true
                                viewModel.fixDialogueScriptWithGemini(dlg.id, mixedText) { result ->
                                    isFixingScript = false
                                    result.onSuccess { fixed -> mixedText = fixed }
                                }
                            },
                            enabled = !isFixingScript,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .testTag("fix_script_gemini_btn"),
                            border = BorderStroke(1.dp, StudioViolet)
                        ) {
                            Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = StudioViolet, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                if (isFixingScript) "Fixing Script with Gemini…" else "Fix Script with Gemini",
                                color = StudioViolet
                            )
                        }
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
                        enabled = !isFixingScript && mixedText.isNotBlank(),
                        onClick = {
                            viewModel.updateDialogueTranslationOnly(dlg, mixedText, emotion, style)
                            editingDialogue = null
                        },
                        modifier = Modifier.testTag("dialogue_save_text_btn")
                    ) {
                        Text("Save Text", color = StudioCyan)
                    }
                    TextButton(
                        enabled = !isFixingScript && mixedText.isNotBlank(),
                        onClick = {
                            viewModel.updateAndRegenerateSingleDialogue(dlg.id, mixedText, emotion)
                            editingDialogue = null
                        },
                        modifier = Modifier.testTag("dialogue_save_voice_btn")
                    ) {
                        Text("Save & Voice", color = StudioViolet)
                    }
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !isFixingScript,
                    onClick = { editingDialogue = null }
                ) {
                    Text("Cancel", color = StudioTextSecondary)
                }
            }
        )
    }

    if (showDictDialog) {
        val existing = editingDictEntry
        val defaultLevel = existing?.dictionaryLevel ?: when (dictLevelFilter) {
            "PROJECT", "ANIME", "GLOBAL" -> dictLevelFilter
            else -> if (activeProject != null) "PROJECT" else "GLOBAL"
        }
        var level by remember(existing?.id, showDictDialog) { mutableStateOf(defaultLevel) }
        var jpTerm by remember(existing?.id, showDictDialog) { mutableStateOf(existing?.japaneseSourceTerm ?: "") }
        var mixedTerm by remember(existing?.id, showDictDialog) {
            mutableStateOf(
                existing?.let { com.example.engine.MixedScriptLanguageEngine.preferredDisplayTerm(it) } ?: ""
            )
        }
        var hindiTerm by remember(existing?.id, showDictDialog) { mutableStateOf(existing?.preferredHindiWording ?: "") }
        var englishTerm by remember(existing?.id, showDictDialog) { mutableStateOf(existing?.preferredEnglishWording ?: "") }
        var pronGuide by remember(existing?.id, showDictDialog) { mutableStateOf(existing?.pronunciationGuidance ?: "") }
        var notes by remember(existing?.id, showDictDialog) { mutableStateOf(existing?.contextNotes ?: "") }

        val scopeBlockedReason = when {
            level == "PROJECT" && activeProject == null -> "Create or open a project first to save Project-level terms."
            level == "ANIME" && activeProject?.animeSeriesName.isNullOrBlank() -> "Active project has no Anime Series name; select Project or Global."
            else -> null
        }

        AlertDialog(
            onDismissRequest = {
                showDictDialog = false
                editingDictEntry = null
            },
            containerColor = StudioSurfaceElevated,
            title = {
                Text(
                    if (existing != null) "Edit Dictionary Term" else "Add Dictionary Term",
                    color = StudioTextPrimary
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Scope Precedence: PROJECT > ANIME > GLOBAL",
                        style = MaterialTheme.typography.labelSmall,
                        color = StudioTextMuted
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("PROJECT", "ANIME", "GLOBAL").forEach { lvl ->
                            FilterChip(
                                selected = level == lvl,
                                onClick = { level = lvl },
                                label = { Text(lvl, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }
                    if (scopeBlockedReason != null) {
                        Text(scopeBlockedReason, style = MaterialTheme.typography.labelSmall, color = StudioAmber)
                    }
                    OutlinedTextField(
                        value = jpTerm,
                        onValueChange = { jpTerm = it },
                        label = { Text("Japanese Source Term") },
                        placeholder = { Text("e.g., 領域展開 or 先輩") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("dict_jp_source_input"),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = mixedTerm,
                        onValueChange = { mixedTerm = it },
                        label = { Text("Preferred Mixed-Script (देवनागरी / English)") },
                        placeholder = { Text("e.g., Domain Expansion or सीनियर") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("dict_mixed_script_input"),
                        singleLine = true
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = hindiTerm,
                            onValueChange = { hindiTerm = it },
                            label = { Text("Hindi (देवनागरी)") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = englishTerm,
                            onValueChange = { englishTerm = it },
                            label = { Text("English Wording") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                    }
                    OutlinedTextField(
                        value = pronGuide,
                        onValueChange = { pronGuide = it },
                        label = { Text("TTS Pronunciation Guidance (Roman / Spoken)") },
                        placeholder = { Text("Optional TTS override — never alters display text") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("dict_pronunciation_input"),
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
                val resolvedPreferred = mixedTerm.trim()
                    .ifBlank { hindiTerm.trim() }
                    .ifBlank { englishTerm.trim() }
                TextButton(
                    enabled = scopeBlockedReason == null && (jpTerm.isNotBlank() || resolvedPreferred.isNotBlank()) && resolvedPreferred.isNotBlank(),
                    onClick = {
                        viewModel.addOrUpdateDictionaryEntry(
                            DictionaryEntryEntity(
                                id = existing?.id ?: 0L,
                                dictionaryLevel = level,
                                projectId = if (level == "PROJECT") activeProject?.id else null,
                                animeSeriesName = if (level == "ANIME") activeProject?.animeSeriesName else null,
                                japaneseSourceTerm = jpTerm.trim(),
                                preferredMixedScript = resolvedPreferred,
                                preferredRomanHinglish = resolvedPreferred,
                                preferredHindiWording = hindiTerm.trim(),
                                preferredEnglishWording = englishTerm.trim(),
                                preserveOriginalEnglish = !com.example.engine.MixedScriptLanguageEngine.containsDevanagari(resolvedPreferred),
                                pronunciationGuidance = pronGuide.trim(),
                                contextNotes = notes.trim(),
                                replacementScope = existing?.replacementScope ?: level
                            )
                        )
                        showDictDialog = false
                        editingDictEntry = null
                    },
                    modifier = Modifier.testTag("dict_save_btn")
                ) {
                    Text(if (existing != null) "Update Term" else "Save Term", color = StudioViolet)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showDictDialog = false
                        editingDictEntry = null
                    }
                ) {
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
        // Mode Switcher: Mixed-Script Translations vs 3-Tier Dictionaries
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = subMode == "TRANSLATIONS",
                    onClick = { subMode = "TRANSLATIONS" },
                    label = { Text("Mixed-Script (${dialogues.size})") },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("tab_translations_mode"),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = StudioViolet.copy(alpha = 0.2f),
                        selectedLabelColor = StudioViolet,
                        containerColor = StudioSurface
                    )
                )
                FilterChip(
                    selected = subMode == "DICTIONARIES",
                    onClick = { subMode = "DICTIONARIES" },
                    label = { Text("Dictionaries (${activeScopedDicts.size})") },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("tab_dictionaries_mode"),
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
                            Text("Mixed-Script Hindi-English (देवनागरी + English)", style = MaterialTheme.typography.titleMedium, color = StudioTextPrimary)
                            Text(
                                "Powered by ${config.geminiModelName} via Firebase AI Logic • Hindi in Devanagari, natural English preserved • 3-tier dictionary precedence.",
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
                        description = "Segment and transcribe dialogue lines in the Transcript tab first before translating into Mixed-Script Hindi-English.",
                        testTagPrefix = "translation_empty"
                    )
                }
            } else {
                items(dialogues, key = { it.id }) { dlg ->
                    val accent = speakerAccentColor(dlg.speakerLabel)
                    val validation = remember(dlg.romanHinglishText) {
                        if (dlg.romanHinglishText.isBlank()) null
                        else com.example.engine.MixedScriptLanguageEngine.validateMixedScript(dlg.romanHinglishText)
                    }
                    val timingPillLabel = when (dlg.timingStatus) {
                        "VERIFIED_ASR_ALIGNED" -> "ASR-Aligned"
                        "ACOUSTIC_ESTIMATED" -> "Acoustic-Est."
                        "MANUAL" -> "Manual Timing"
                        else -> null
                    }
                    val timingPillColor = when (dlg.timingStatus) {
                        "VERIFIED_ASR_ALIGNED" -> StudioEmerald
                        "ACOUSTIC_ESTIMATED" -> StudioAmber
                        else -> StudioTextSecondary
                    }

                    StudioCard(padding = 14.dp) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                StatusPill(text = dlg.dialogueId, color = accent)
                                StatusPill(text = dlg.emotion, color = StudioCyan)
                                if (timingPillLabel != null) {
                                    StatusPill(text = timingPillLabel, color = timingPillColor)
                                }
                                Text(
                                    text = "${"%.2f".format(dlg.originalDurationMs / 1000.0)}s",
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                                    color = StudioTextMuted
                                )
                            }
                            IconButton(
                                onClick = { editingDialogue = dlg },
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTag("edit_dialogue_${dlg.dialogueId}")
                            ) {
                                Icon(Icons.Default.Edit, contentDescription = "Edit translation", tint = StudioViolet, modifier = Modifier.size(18.dp))
                            }
                        }

                        if (dlg.sourceDialogueRef.isNotBlank() && dlg.sourceDialogueRef != dlg.dialogueId) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Split sentence from ${dlg.sourceDialogueRef}",
                                style = MaterialTheme.typography.labelSmall,
                                color = StudioTextMuted
                            )
                        }

                        if (validation != null && !validation.isValid) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                StatusPill(text = "Legacy Roman Hindi — Needs Fix", color = StudioAmber)
                                TextButton(
                                    onClick = { editingDialogue = dlg },
                                    modifier = Modifier.heightIn(min = 48.dp)
                                ) {
                                    Text("Fix Script", color = StudioViolet)
                                }
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
                                        Text("MIXED-SCRIPT HINDI-ENGLISH", style = MaterialTheme.typography.labelSmall, color = StudioViolet)
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
                                        Text("MIXED-SCRIPT HINDI-ENGLISH", style = MaterialTheme.typography.labelSmall, color = StudioViolet)
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
                                text = "TTS Pronunciation Feed: ${dlg.ttsNormalizedText}",
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                                color = StudioTextMuted
                            )
                        }
                    }
                }
            }
        } else {
            // 3-Tier Dictionaries: Strictly Isolated to Active Project & Anime Series + Global
            item {
                StudioSectionHeader(
                    title = "Terminology Dictionaries",
                    subtitle = "Active Scope Precedence: Project > Anime > Global (isolated per project)",
                    trailing = {
                        Button(
                            onClick = {
                                editingDictEntry = null
                                showDictDialog = true
                            },
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("dict_add_btn"),
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
                OutlinedTextField(
                    value = dictSearchQuery,
                    onValueChange = { dictSearchQuery = it },
                    label = { Text("Search Japanese, Devanagari, English, or notes") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("dict_search_input"),
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        "ALL_ACTIVE" to "All Active",
                        "PROJECT" to "Project",
                        "ANIME" to "Anime",
                        "GLOBAL" to "Global"
                    ).forEach { (key, label) ->
                        FilterChip(
                            selected = dictLevelFilter == key,
                            onClick = { dictLevelFilter = key },
                            label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
            }

            // Strictly isolate entries belonging to active project / active anime series / global
            val strictlyIsolatedEntries = dictionaries.filter { entry ->
                val matchesScopeIsolation = when (entry.dictionaryLevel.uppercase()) {
                    "PROJECT" -> activeProject != null && entry.projectId == activeProject!!.id
                    "ANIME" -> !activeProject?.animeSeriesName.isNullOrBlank() &&
                        entry.animeSeriesName.equals(activeProject!!.animeSeriesName, ignoreCase = true)
                    else -> entry.projectId == null && entry.animeSeriesName.isNullOrBlank()
                }
                val matchesTab = dictLevelFilter == "ALL_ACTIVE" ||
                    entry.dictionaryLevel.equals(dictLevelFilter, ignoreCase = true)
                val q = dictSearchQuery.trim()
                val matchesSearch = q.isEmpty() ||
                    entry.japaneseSourceTerm.contains(q, ignoreCase = true) ||
                    entry.preferredMixedScript.contains(q, ignoreCase = true) ||
                    entry.preferredRomanHinglish.contains(q, ignoreCase = true) ||
                    entry.preferredHindiWording.contains(q, ignoreCase = true) ||
                    entry.preferredEnglishWording.contains(q, ignoreCase = true) ||
                    entry.pronunciationGuidance.contains(q, ignoreCase = true) ||
                    entry.contextNotes.contains(q, ignoreCase = true)
                matchesScopeIsolation && matchesTab && matchesSearch
            }.sortedWith(
                compareBy<DictionaryEntryEntity> {
                    when (it.dictionaryLevel.uppercase()) {
                        "PROJECT" -> 0
                        "ANIME" -> 1
                        else -> 2
                    }
                }.thenByDescending { it.updatedAt }
            )

            if (strictlyIsolatedEntries.isEmpty()) {
                item {
                    StudioEmptyState(
                        icon = Icons.Default.Book,
                        title = if (dictSearchQuery.isBlank()) "No Dictionary Terms in This Scope" else "No Matching Dictionary Terms",
                        description = "Add character names, anime attacks, Devanagari Hindi wording, English loanwords, and TTS pronunciation hints. Project entries override Anime entries, which override Global.",
                        primaryActionLabel = "Add Term",
                        onPrimaryAction = {
                            editingDictEntry = null
                            showDictDialog = true
                        }
                    )
                }
            } else {
                items(strictlyIsolatedEntries, key = { it.id }) { entry ->
                    val preferred = com.example.engine.MixedScriptLanguageEngine.preferredDisplayTerm(entry)
                    val scopeColor = when (entry.dictionaryLevel.uppercase()) {
                        "PROJECT" -> StudioViolet
                        "ANIME" -> StudioCyan
                        else -> StudioEmerald
                    }
                    StudioCard(padding = 12.dp) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    StatusPill(text = entry.dictionaryLevel, color = scopeColor)
                                    Text(
                                        text = "${entry.japaneseSourceTerm.ifBlank { "(Term)" }}  →  $preferred",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = StudioTextPrimary
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                val detailParts = buildList {
                                    if (entry.preferredHindiWording.isNotBlank()) add("HI: ${entry.preferredHindiWording}")
                                    if (entry.preferredEnglishWording.isNotBlank()) add("EN: ${entry.preferredEnglishWording}")
                                    if (entry.pronunciationGuidance.isNotBlank()) add("TTS: ${entry.pronunciationGuidance}")
                                    if (entry.contextNotes.isNotBlank()) add(entry.contextNotes)
                                }
                                if (detailParts.isNotEmpty()) {
                                    Text(
                                        text = detailParts.joinToString(" • "),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = StudioTextSecondary
                                    )
                                }
                            }
                            Row {
                                IconButton(
                                    onClick = {
                                        editingDictEntry = entry
                                        showDictDialog = true
                                    },
                                    modifier = Modifier
                                        .size(48.dp)
                                        .testTag("dict_edit_${entry.id}")
                                ) {
                                    Icon(Icons.Default.Edit, contentDescription = "Edit term", tint = StudioViolet)
                                }
                                IconButton(
                                    onClick = { viewModel.deleteDictionaryEntry(entry.id) },
                                    modifier = Modifier
                                        .size(48.dp)
                                        .testTag("dict_delete_${entry.id}")
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
}
