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
internal fun UserWordbookScreen(
    cards: List<VocabularyCard>,
    contexts: List<StoredContext>,
    definitionCandidates: List<StoredDefinitionCandidate>,
    events: List<StoredReviewEvent>,
    enrichmentStates: Map<String, DictionaryEnrichmentState>,
    message: String,
    weakWordIds: Set<String>,
    timezone: ZoneId,
    needsNotificationPermission: Boolean,
    onRequestNotificationPermission: () -> Unit,
    onEditSave: (VocabularyCard, String, String) -> Unit,
    onRetryEnrichment: (VocabularyCard) -> Unit,
    onSelectDefinitionCandidate: (VocabularyCard, String) -> Unit,
    onReview: (VocabularyCard, ReviewResponse) -> Unit,
    onDelete: (VocabularyCard) -> Unit
) {
    WordbookScreen(
        title = stringResource(R.string.tab_user_words),
        cards = cards,
        contexts = contexts,
        definitionCandidates = definitionCandidates,
        events = events,
        enrichmentStates = enrichmentStates,
        message = message,
        weakWordIds = weakWordIds,
        timezone = timezone,
        emptyText = "아직 저장된 단어가 없습니다.",
        header = {
            val now = Instant.now()
            val dueToday = cards.count { card ->
                card.meaningKo.isNotBlank() &&
                    card.deletedAt == null &&
                    card.status != CardStatus.MASTERED &&
                    card.status != CardStatus.SUSPENDED &&
                    !card.dueAt.isAfter(now)
            }
            Text(
                text = stringResource(
                    R.string.summary_remaining_reviews,
                    dueToday,
                    weakWordIds.size,
                    cards.size
                ),
                style = MaterialTheme.typography.titleMedium
            )
            if (needsNotificationPermission) {
                NotificationPermissionCard(onRequestNotificationPermission)
            }
        },
        onEditSave = onEditSave,
        onRetryEnrichment = onRetryEnrichment,
        onSelectDefinitionCandidate = onSelectDefinitionCandidate,
        onReview = onReview,
        onRecommendationAction = { _, _ -> },
        onDelete = onDelete
    )
}

@Composable
internal fun RecommendationWordbookScreen(
    cards: List<VocabularyCard>,
    recommendationStatus: String,
    contexts: List<StoredContext>,
    definitionCandidates: List<StoredDefinitionCandidate>,
    events: List<StoredReviewEvent>,
    enrichmentStates: Map<String, DictionaryEnrichmentState>,
    message: String,
    weakWordIds: Set<String>,
    timezone: ZoneId,
    onEditSave: (VocabularyCard, String, String) -> Unit,
    onRetryEnrichment: (VocabularyCard) -> Unit,
    onSelectDefinitionCandidate: (VocabularyCard, String) -> Unit,
    onReview: (VocabularyCard, ReviewResponse) -> Unit,
    onRecommendationAction: (VocabularyCard, String) -> Unit,
    onDelete: (VocabularyCard) -> Unit
) {
    WordbookScreen(
        title = stringResource(R.string.tab_recommendations),
        cards = cards,
        contexts = contexts,
        definitionCandidates = definitionCandidates,
        events = events,
        enrichmentStates = enrichmentStates,
        message = message,
        weakWordIds = weakWordIds,
        timezone = timezone,
        emptyText = recommendationStatus,
        header = {
            Text(
                text = "추천 단어 ${cards.size}개",
                style = MaterialTheme.typography.titleMedium
            )
            Text(recommendationStatus, style = MaterialTheme.typography.bodyMedium)
        },
        onEditSave = onEditSave,
        onRetryEnrichment = onRetryEnrichment,
        onSelectDefinitionCandidate = onSelectDefinitionCandidate,
        onReview = onReview,
        onRecommendationAction = onRecommendationAction,
        onDelete = onDelete
    )
}

@Composable
internal fun WordbookScreen(
    title: String,
    cards: List<VocabularyCard>,
    contexts: List<StoredContext>,
    definitionCandidates: List<StoredDefinitionCandidate>,
    events: List<StoredReviewEvent>,
    enrichmentStates: Map<String, DictionaryEnrichmentState>,
    message: String,
    weakWordIds: Set<String>,
    timezone: ZoneId,
    emptyText: String,
    header: @Composable () -> Unit,
    onEditSave: (VocabularyCard, String, String) -> Unit,
    onRetryEnrichment: (VocabularyCard) -> Unit,
    onSelectDefinitionCandidate: (VocabularyCard, String) -> Unit,
    onReview: (VocabularyCard, ReviewResponse) -> Unit,
    onRecommendationAction: (VocabularyCard, String) -> Unit,
    onDelete: (VocabularyCard) -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(title, style = MaterialTheme.typography.headlineSmall)
        }
        item {
            header()
        }
        if (message.isNotBlank()) {
            item {
                StatusSnackbar(message = message)
            }
        }
        if (cards.isEmpty()) {
            item {
                Text(emptyText, style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            items(cards, key = { it.id }) { card ->
                CardSummary(
                    card = card,
                    weak = card.id in weakWordIds,
                    recommendationReason = com.vocabreminder.android.recommendation.RecommendationEngine.reason(
                        card.lemma, contexts.filter { it.captureMethod != CaptureMethod.RECOMMENDATION }.takeLast(50).map { it.sourceSentence }
                    ),
                    contexts = contexts.filter { it.cardId == card.id },
                    definitionCandidates = definitionCandidates.filter { it.cardId == card.id },
                    events = events.filter { it.cardId == card.id },
                    enrichmentState = enrichmentStates[card.id],
                    timezone = timezone,
                    onEditSave = { editedMeaning, editedExample -> onEditSave(card, editedMeaning, editedExample) },
                    onRetryEnrichment = { onRetryEnrichment(card) },
                    onSelectDefinitionCandidate = { candidateId ->
                        onSelectDefinitionCandidate(card, candidateId)
                    },
                    onReview = { response -> onReview(card, response) },
                    onRecommendationAction = { action -> onRecommendationAction(card, action) },
                    onDelete = { onDelete(card) }
                )
            }
        }
    }
}

@Composable
internal fun NotificationPermissionCard(onRequestNotificationPermission: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("알림이 꺼져 있어 복습 알림을 받을 수 없습니다.")
            Text("권한을 허용하면 저장한 단어가 due 시각에 알림으로 도착합니다.")
            Button(onClick = onRequestNotificationPermission) {
                Text("알림 복구 안내")
            }
        }
    }
}

@Composable
internal fun StatusSnackbar(
    message: String,
    onDirectInput: (() -> Unit)? = null
) {
    val isError = message.contains("뜻을 찾지 못했습니다") || message.contains("실패")
    Snackbar(
        containerColor = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.inverseSurface,
        contentColor = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.inverseOnSurface,
        actionContentColor = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.inversePrimary,
        action = if (onDirectInput != null) {
            {
                TextButton(onClick = onDirectInput) {
                    Text(stringResource(R.string.manual_input_button))
                }
            }
        } else {
            null
        }
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall
        )
    }
}
