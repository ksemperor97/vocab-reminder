package com.vocabreminder.android

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.vocabreminder.android.capture.CaptureAnalysis
import com.vocabreminder.android.capture.CaptureMethod
import com.vocabreminder.android.capture.CaptureNormalizer
import com.vocabreminder.android.capture.CaptureProvenance
import com.vocabreminder.android.capture.CaptureProvenanceExtractor
import com.vocabreminder.android.data.CardHistoryBuilder
import com.vocabreminder.android.data.CardHistoryEntry
import com.vocabreminder.android.data.AndroidReadinessEvaluator
import com.vocabreminder.android.data.AndroidReadinessInputs
import com.vocabreminder.android.data.AndroidReadinessItem
import com.vocabreminder.android.data.AndroidReadinessKind
import com.vocabreminder.android.data.AndroidReadinessLevel
import com.vocabreminder.android.data.CloudSyncCredentials
import com.vocabreminder.android.data.CloudSyncStatus
import com.vocabreminder.android.data.DictionaryEnrichmentFailure
import com.vocabreminder.android.data.DictionaryEnrichmentState
import com.vocabreminder.android.data.DictionaryEnrichmentStatus
import com.vocabreminder.android.data.LocalVocabularyStore
import com.vocabreminder.android.data.StoredContext
import com.vocabreminder.android.data.StoredDefinitionCandidate
import com.vocabreminder.android.data.StoredReviewEvent
import com.vocabreminder.android.data.UserSettingsData
import com.vocabreminder.android.data.VocabularyCard
import com.vocabreminder.android.dictionary.DictionaryResolver
import com.vocabreminder.android.dictionary.DictionaryResult
import com.vocabreminder.android.notifications.DailyReviewPlanner
import com.vocabreminder.android.notifications.QuietHoursBoundaryScheduler
import com.vocabreminder.android.notifications.QuietHoursWorker
import com.vocabreminder.android.notifications.ReminderNotifications
import com.vocabreminder.android.notifications.ReminderScheduler
import com.vocabreminder.android.scheduler.CardStatus
import com.vocabreminder.android.scheduler.DueTimeJitter
import com.vocabreminder.android.scheduler.ReviewResponse
import com.vocabreminder.android.scheduler.Scheduler
import com.vocabreminder.android.scheduler.Wordbook
import com.vocabreminder.android.sync.CloudSyncClient
import com.vocabreminder.android.sync.CloudSyncConfig
import com.vocabreminder.android.sync.CloudSyncException
import com.vocabreminder.android.sync.SyncWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit


@Composable
internal fun AddWordSheetContent(
    word: String,
    meaning: String,
    example: String,
    message: String,
    manualFieldsVisible: Boolean,
    pendingAnalysis: CaptureAnalysis?,
    preview: DictionaryResult?,
    previewMeaning: String,
    previewExample: String,
    onWordChange: (String) -> Unit,
    onMeaningChange: (String) -> Unit,
    onExampleChange: (String) -> Unit,
    onPreview: () -> Unit,
    onShowManualFields: () -> Unit,
    onDirectInputFromPreview: () -> Unit,
    onClipboard: () -> Unit,
    onSaveManual: () -> Unit,
    onCandidateSelected: (CaptureAnalysis, String) -> Unit,
    onPreviewMeaningChange: (String) -> Unit,
    onPreviewExampleChange: (String) -> Unit,
    onSavePreview: (DictionaryResult) -> Unit,
    onCancelPreview: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            AddWordForm(
                word = word,
                meaning = meaning,
                example = example,
                manualFieldsVisible = manualFieldsVisible,
                onWordChange = onWordChange,
                onMeaningChange = onMeaningChange,
                onExampleChange = onExampleChange,
                onPreview = onPreview,
                onShowManualFields = onShowManualFields,
                onClipboard = onClipboard,
                onSave = onSaveManual
            )
        }
        if (message.isNotBlank()) {
            item {
                StatusSnackbar(
                    message = message,
                    onDirectInput = if (message.contains("뜻을 찾지 못했습니다")) onDirectInputFromPreview else null
                )
            }
        }
        pendingAnalysis?.let { analysis ->
            item {
                CandidateSelection(analysis) { selected ->
                    onCandidateSelected(analysis, selected)
                }
            }
        }
        preview?.let { result ->
            item {
                SavePreview(
                    result = result,
                    meaning = previewMeaning,
                    example = previewExample,
                    onMeaningChange = onPreviewMeaningChange,
                    onExampleChange = onPreviewExampleChange,
                    onSave = { onSavePreview(result) },
                    onCancel = onCancelPreview
                )
            }
        }
        item {
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
internal fun AddWordForm(
    word: String,
    meaning: String,
    example: String,
    manualFieldsVisible: Boolean,
    onWordChange: (String) -> Unit,
    onMeaningChange: (String) -> Unit,
    onExampleChange: (String) -> Unit,
    onPreview: () -> Unit,
    onShowManualFields: () -> Unit,
    onClipboard: () -> Unit,
    onSave: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedTextField(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("word_field"),
                value = word,
                onValueChange = onWordChange,
                label = { Text("단어 또는 문장") },
                singleLine = true
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    modifier = Modifier.testTag("preview_button"),
                    onClick = onPreview
                ) {
                    Text("미리보기")
                }
                Button(
                    modifier = Modifier.testTag("clipboard_button"),
                    onClick = onClipboard
                ) {
                    Text("클립보드")
                }
                Button(
                    modifier = Modifier.testTag("manual_input_button"),
                    onClick = onShowManualFields
                ) {
                    Text(stringResource(R.string.manual_input_button))
                }
            }
            if (manualFieldsVisible) {
                OutlinedTextField(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("meaning_field"),
                    value = meaning,
                    onValueChange = onMeaningChange,
                    label = { Text("뜻") },
                    singleLine = true
                )
                OutlinedTextField(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("example_field"),
                    value = example,
                    onValueChange = onExampleChange,
                    label = { Text("예문") }
                )
                Button(
                    modifier = Modifier.testTag("save_button"),
                    onClick = onSave
                ) {
                    Text("저장")
                }
            }
        }
    }
}

@Composable
internal fun CandidateSelection(
    analysis: CaptureAnalysis,
    onSelect: (String) -> Unit
) {
    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("어떤 단어를 저장할까요?", style = MaterialTheme.typography.titleMedium)
            Text(
                text = analysis.normalizedText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(analysis.candidates) { candidate ->
                    val normalized = CaptureNormalizer.lemma(candidate).lowercase()
                    AssistChip(
                        modifier = Modifier.testTag("candidate_$normalized"),
                        onClick = { onSelect(candidate) },
                        label = { Text(normalized) }
                    )
                }
            }
        }
    }
}

@Composable
internal fun SavePreview(
    result: DictionaryResult,
    meaning: String,
    example: String,
    onMeaningChange: (String) -> Unit,
    onExampleChange: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit
) {
    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(result.displayWord, style = MaterialTheme.typography.titleLarge)
            PreviewInfoRow("원형", result.lemma)
            if (result.partOfSpeech.isNotBlank()) {
                PreviewInfoRow(
                    label = "품사",
                    value = listOf(result.partOfSpeech, result.definitionEn)
                        .filter { it.isNotBlank() }
                        .joinToString(" · ")
                )
            }
            OutlinedTextField(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("preview_meaning_field"),
                value = meaning,
                onValueChange = onMeaningChange,
                label = { Text("뜻") },
                singleLine = true
            )
            OutlinedTextField(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("preview_example_field"),
                value = example,
                onValueChange = onExampleChange,
                label = { Text("예문") }
            )
            PreviewInfoRow(
                "출처",
                CaptureProvenance(
                    sourceApp = result.sourceApp,
                    sourceUrl = result.sourceUrl,
                    pageTitle = result.sourceTitle
                ).sourceLabel
            )
            SourceLabel(result.sourceType)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    modifier = Modifier.testTag("preview_save_button"),
                    onClick = onSave
                ) {
                    Text("저장")
                }
                Button(onClick = onCancel) {
                    Text("취소")
                }
            }
        }
    }
}

@Composable
internal fun PreviewInfoRow(label: String, value: String) {
    if (value.isNotBlank()) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
internal fun SourceLabel(label: String) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shape = MaterialTheme.shapes.small
    ) {
        Text(
            text = label.ifBlank { "user" },
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall
        )
    }
}
