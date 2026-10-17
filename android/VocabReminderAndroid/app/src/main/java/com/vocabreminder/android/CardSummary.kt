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
internal fun CardSummary(
    card: VocabularyCard,
    weak: Boolean,
    recommendationReason: String = "",
    contexts: List<StoredContext>,
    definitionCandidates: List<StoredDefinitionCandidate>,
    events: List<StoredReviewEvent>,
    enrichmentState: DictionaryEnrichmentState?,
    timezone: ZoneId,
    onEditSave: (String, String) -> Unit,
    onRetryEnrichment: () -> Unit,
    onSelectDefinitionCandidate: (String) -> Unit,
    onReview: (ReviewResponse) -> Unit,
    onRecommendationAction: (String) -> Unit,
    onDelete: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var editing by remember(card.id) { mutableStateOf(false) }
    var editMeaning by remember(card.id, card.meaningKo) { mutableStateOf(card.meaningKo) }
    var editExample by remember(card.id, card.exampleSentence) { mutableStateOf(card.exampleSentence) }
    var visibleHistoryCount by remember(card.id) { mutableStateOf(RECENT_HISTORY_COUNT) }
    val history = CardHistoryBuilder.build(card, contexts, definitionCandidates, events)
    val hasDefinitionConflict = history.any { it is CardHistoryEntry.SyncConflictEntry }
    Card(
        modifier = Modifier.clickable { expanded = !expanded }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(text = card.displayWord, style = MaterialTheme.typography.titleMedium)
            if (card.wordbook == Wordbook.RECOMMENDED && recommendationReason.isNotBlank()) {
                Text(recommendationReason, style = MaterialTheme.typography.labelSmall)
            }
            Text(
                text = card.meaningKo.ifBlank {
                    card.definitionEn.ifBlank { "뜻 없음 · 알림 보류" }
                },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { StatusBadge(card) }
                item { EnrichmentBadge(card, enrichmentState) }
                if (weak) {
                    item {
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = MaterialTheme.shapes.small
                        ) {
                            Text(
                                text = "취약",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
                if (hasDefinitionConflict) {
                    item {
                        SmallBadge(
                            text = "뜻 대안 보존",
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                            contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
                }
            }
            Text(
                text = "다음 복습: ${relativeDueLabel(card.dueAt, timezone)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (expanded) {
                if (card.exampleSentence.isNotBlank()) {
                    Text(
                        text = "예문: ${card.exampleSentence}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (enrichmentState?.status == DictionaryEnrichmentStatus.FAILED && card.meaningKo.isBlank()) {
                    Button(
                        modifier = Modifier.testTag("retry_enrichment_${card.lemma}"),
                        onClick = onRetryEnrichment
                    ) {
                        Text("다시 해석")
                    }
                }
                if (history.isNotEmpty()) {
                    Text("카드 전기", style = MaterialTheme.typography.titleSmall)
                    history.take(visibleHistoryCount).forEach { entry ->
                        CardHistoryRow(
                            entry = entry,
                            timezone = timezone,
                            onSelectDefinitionCandidate = onSelectDefinitionCandidate
                        )
                    }
                    if (history.size > RECENT_HISTORY_COUNT) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (visibleHistoryCount < history.size) {
                                TextButton(
                                    modifier = Modifier.testTag("show_more_history_${card.lemma}"),
                                    onClick = {
                                        visibleHistoryCount = if (visibleHistoryCount == RECENT_HISTORY_COUNT) {
                                            minOf(HISTORY_PAGE_SIZE, history.size)
                                        } else {
                                            minOf(visibleHistoryCount + HISTORY_PAGE_SIZE, history.size)
                                        }
                                    }
                                ) {
                                    Text(
                                        if (visibleHistoryCount == RECENT_HISTORY_COUNT) {
                                            "전체 보기 (${history.size})"
                                        } else {
                                            "${minOf(HISTORY_PAGE_SIZE, history.size - visibleHistoryCount)}개 더"
                                        }
                                    )
                                }
                            }
                            if (visibleHistoryCount > RECENT_HISTORY_COUNT) {
                                TextButton(onClick = { visibleHistoryCount = RECENT_HISTORY_COUNT }) {
                                    Text("최근 3개만")
                                }
                            }
                        }
                    }
                }
                if (card.wordbook == Wordbook.RECOMMENDED && card.recommendationStartedAt == null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            modifier = Modifier.testTag("start_recommendation_${card.lemma}"),
                            onClick = { onRecommendationAction(ReminderNotifications.RECOMMENDATION_START) }
                        ) {
                            Text(stringResource(R.string.card_recommendation_start_button))
                        }
                        TextButton(
                            modifier = Modifier.testTag("known_recommendation_${card.lemma}"),
                            onClick = { onRecommendationAction(ReminderNotifications.RECOMMENDATION_KNOWN) }
                        ) {
                            Text(stringResource(R.string.card_recommendation_known_button))
                        }
                        TextButton(
                            modifier = Modifier.testTag("dismiss_recommendation_${card.lemma}"),
                            onClick = { onRecommendationAction(ReminderNotifications.RECOMMENDATION_DISMISS) }
                        ) {
                            Text(stringResource(R.string.card_recommendation_dismiss_button))
                        }
                    }
                } else if (card.meaningKo.isNotBlank()) {
                    if (card.status == CardStatus.SUSPENDED) {
                        Button(
                            modifier = Modifier.testTag("restore_card_${card.lemma}"),
                            onClick = { onReview(ReviewResponse.RESTORE) }
                        ) {
                            Text(stringResource(R.string.card_restore_button))
                        }
                    } else {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            item {
                                Button(
                                    modifier = Modifier.testTag("review_remembered_${card.lemma}"),
                                    onClick = { onReview(ReviewResponse.REMEMBERED) }
                                ) {
                                    Text(stringResource(R.string.card_review_remembered_button))
                                }
                            }
                            item {
                                Button(
                                    modifier = Modifier.testTag("review_forgotten_${card.lemma}"),
                                    onClick = { onReview(ReviewResponse.FORGOTTEN) }
                                ) {
                                    Text(stringResource(R.string.card_review_forgotten_button))
                                }
                            }
                            item {
                                TextButton(
                                    modifier = Modifier.testTag("review_unavailable_${card.lemma}"),
                                    onClick = { onReview(ReviewResponse.UNAVAILABLE) }
                                ) {
                                    Text(stringResource(R.string.card_review_unavailable_button))
                                }
                            }
                            item {
                                TextButton(
                                    modifier = Modifier.testTag("suspend_card_${card.lemma}"),
                                    onClick = { onReview(ReviewResponse.SUSPEND) }
                                ) {
                                    Text(stringResource(R.string.card_suspend_button))
                                }
                            }
                        }
                    }
                }
                if (BuildConfig.DEBUG) {
                    val weakLabel = if (weak) " · weak" else ""
                    Text(
                        text = "wordbook=${card.wordbook.name.lowercase()} · status=${card.status.name.lowercase()} · interval=${card.intervalDays}d · due=${card.dueAt}$weakLabel",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (editing) {
                    OutlinedTextField(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("edit_meaning_${card.lemma}"),
                        value = editMeaning,
                        onValueChange = { editMeaning = it },
                        label = { Text(stringResource(R.string.card_edit_meaning_label)) },
                        singleLine = true
                    )
                    OutlinedTextField(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("edit_example_${card.lemma}"),
                        value = editExample,
                        onValueChange = { editExample = it },
                        label = { Text(stringResource(R.string.card_edit_example_label)) }
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            modifier = Modifier.testTag("save_edit_${card.lemma}"),
                            onClick = {
                                onEditSave(editMeaning, editExample)
                                editing = false
                            }
                        ) {
                            Text(stringResource(R.string.card_edit_save_button))
                        }
                        TextButton(
                            modifier = Modifier.testTag("cancel_edit_${card.lemma}"),
                            onClick = {
                                editMeaning = card.meaningKo
                                editExample = card.exampleSentence
                                editing = false
                            }
                        ) {
                            Text(stringResource(R.string.card_edit_cancel_button))
                        }
                    }
                } else {
                    Button(
                        modifier = Modifier.testTag("edit_card_${card.lemma}"),
                        onClick = { editing = true }
                    ) {
                        Text(stringResource(R.string.card_edit_button))
                    }
                }
            }
            Button(
                modifier = Modifier.testTag("delete_card_${card.lemma}"),
                onClick = onDelete
            ) {
                Text("삭제")
            }
        }
    }
}

@Composable
private fun CardHistoryRow(
    entry: CardHistoryEntry,
    timezone: ZoneId,
    onSelectDefinitionCandidate: (String) -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            when (entry) {
                is CardHistoryEntry.ContextEntry -> {
                    val context = entry.context
                    Text("문맥 캡처", style = MaterialTheme.typography.labelLarge)
                    Text(
                        context.sourceSentence.ifBlank { "캡처 문장 없음" },
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        CaptureProvenance(
                            sourceApp = context.sourceApp,
                            sourceUrl = context.sourceUrl,
                            pageTitle = context.pageTitle
                        ).sourceLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                is CardHistoryEntry.DefinitionEntry -> {
                    val candidate = entry.candidate
                    Text("뜻 후보", style = MaterialTheme.typography.labelLarge)
                    Text(
                        candidate.parsedMeaningKo.ifBlank {
                            candidate.parsedDefinitionEn.ifBlank { "해석 내용 없음" }
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (candidate.parsedDefinitionEn.isNotBlank() && candidate.parsedMeaningKo.isNotBlank()) {
                        Text(
                            candidate.parsedDefinitionEn,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        item {
                            SmallBadge(
                                text = candidate.source.ifBlank { "source 없음" },
                                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        }
                        item {
                            SmallBadge(
                                text = "신뢰도 ${(candidate.confidence.coerceIn(0.0, 1.0) * 100).toInt()}%",
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                        if (entry.isCurrentPrimary) {
                            item {
                                SmallBadge(
                                    text = "현재 뜻",
                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                        if (entry.isConflictCandidate) {
                            item {
                                SmallBadge(
                                    text = "충돌/대안",
                                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                            }
                        }
                    }
                    if (!entry.isCurrentPrimary && candidate.parsedMeaningKo.isNotBlank()) {
                        TextButton(
                            modifier = Modifier.testTag("select_candidate_${candidate.id}"),
                            onClick = { onSelectDefinitionCandidate(candidate.id) }
                        ) {
                            Text("이 뜻으로 복원")
                        }
                    }
                }
                is CardHistoryEntry.ReviewEntry -> {
                    val event = entry.event
                    val action = event.metadata["action"]
                    Text(
                        when (action) {
                            "select_definition_candidate" -> "뜻 후보 복원"
                            "manual_edit" -> "직접 수정"
                            else -> "학습 기록 · ${reviewResponseLabel(event.response)}"
                        },
                        style = MaterialTheme.typography.labelLarge
                    )
                    Text(
                        "${statusLabel(event.previousStatus)} → ${statusLabel(event.nextStatus)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                is CardHistoryEntry.SyncConflictEntry -> {
                    Text("보존된 뜻 대안", style = MaterialTheme.typography.labelLarge)
                    Text(
                        "오프라인 또는 동기화 편집에서 선택된 서로 다른 뜻 후보 ${entry.candidateIds.size}개를 모두 보존했습니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(
                formatHistoryTime(entry.occurredAt, timezone),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun EnrichmentBadge(card: VocabularyCard, state: DictionaryEnrichmentState?) {
    val effectiveState = state ?: DictionaryEnrichmentState(
        cardId = card.id,
        status = if (card.meaningKo.isBlank()) {
            DictionaryEnrichmentStatus.PENDING
        } else if (card.definitionSourcePrimary == "user") {
            DictionaryEnrichmentStatus.USER_LOCKED
        } else {
            DictionaryEnrichmentStatus.RESOLVED
        }
    )
    val baseLabel = when (effectiveState.status) {
        DictionaryEnrichmentStatus.PENDING -> "뜻 찾는 중"
        DictionaryEnrichmentStatus.FAILED -> when (effectiveState.failure) {
            DictionaryEnrichmentFailure.NETWORK -> "해석 실패 · 네트워크"
            DictionaryEnrichmentFailure.NOT_FOUND -> "해석 실패 · 사전에 없음"
            DictionaryEnrichmentFailure.PROVIDER_FAILURE -> "해석 실패 · 제공자"
            null -> "해석 실패"
        }
        DictionaryEnrichmentStatus.RESOLVED -> "자동 해석 완료"
        DictionaryEnrichmentStatus.USER_LOCKED -> "사용자 뜻 고정"
    }
    val failed = effectiveState.status == DictionaryEnrichmentStatus.FAILED
    val label = if (failed) {
        shortRequestId(effectiveState.requestId)
            ?.let { "$baseLabel · #$it" }
            ?: baseLabel
    } else {
        baseLabel
    }
    SmallBadge(
        text = label,
        containerColor = if (failed) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        contentColor = if (failed) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer
    )
}

internal fun shortRequestId(value: String?): String? {
    return value?.trim()?.takeIf { it.isNotBlank() }?.take(8)
}

@Composable
private fun SmallBadge(text: String, containerColor: Color, contentColor: Color) {
    Surface(
        color = containerColor,
        contentColor = contentColor,
        shape = MaterialTheme.shapes.small
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall
        )
    }
}

@Composable
private fun StatusBadge(card: VocabularyCard) {
    val missingMeaning = card.meaningKo.isBlank()
    val containerColor = when {
        missingMeaning -> MaterialTheme.colorScheme.surface
        card.status == CardStatus.NEW -> MaterialTheme.colorScheme.primaryContainer
        card.status == CardStatus.LEARNING -> MaterialTheme.colorScheme.secondaryContainer
        card.status == CardStatus.REVIEW -> MaterialTheme.colorScheme.tertiaryContainer
        card.status == CardStatus.RELEARNING -> MaterialTheme.colorScheme.errorContainer
        card.status == CardStatus.MASTERED -> Color(0xFFE0E0E0)
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = when {
        missingMeaning -> MaterialTheme.colorScheme.error
        card.status == CardStatus.NEW -> MaterialTheme.colorScheme.onPrimaryContainer
        card.status == CardStatus.LEARNING -> MaterialTheme.colorScheme.onSecondaryContainer
        card.status == CardStatus.REVIEW -> MaterialTheme.colorScheme.onTertiaryContainer
        card.status == CardStatus.RELEARNING -> MaterialTheme.colorScheme.onErrorContainer
        card.status == CardStatus.MASTERED -> Color(0xFF424242)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        color = containerColor,
        contentColor = contentColor,
        shape = MaterialTheme.shapes.small,
        border = if (missingMeaning) BorderStroke(1.dp, MaterialTheme.colorScheme.error) else null
    ) {
        Text(
            text = if (missingMeaning) "뜻 없음·알림 보류" else statusLabel(card.status),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall
        )
    }
}

private fun formatHistoryTime(instant: Instant, timezone: ZoneId): String {
    return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        .withZone(timezone)
        .format(instant)
}

private const val RECENT_HISTORY_COUNT = 3
private const val HISTORY_PAGE_SIZE = 20

private fun statusLabel(status: CardStatus): String {
    return when (status) {
        CardStatus.NEW -> "새 단어"
        CardStatus.LEARNING -> "배우는 중"
        CardStatus.REVIEW -> "복습"
        CardStatus.RELEARNING -> "재학습"
        CardStatus.MASTERED -> "완료"
        CardStatus.SUSPENDED -> "보류"
    }
}

private fun reviewResponseLabel(response: ReviewResponse): String {
    return when (response) {
        ReviewResponse.REMEMBERED -> "외웠다"
        ReviewResponse.FORGOTTEN -> "못외웠다"
        ReviewResponse.UNAVAILABLE -> "나중에 하기"
        ReviewResponse.REVEAL -> "뜻 보기"
        ReviewResponse.MANUAL_EDIT -> "직접 수정"
        ReviewResponse.SUSPEND -> "보류"
        ReviewResponse.RESTORE -> "복원"
    }
}

private fun relativeDueLabel(dueAt: Instant, timezone: ZoneId): String {
    val now = Instant.now()
    if (!dueAt.isAfter(now)) {
        return "지금"
    }
    val today = now.atZone(timezone).toLocalDate()
    val dueDateTime = dueAt.atZone(timezone)
    val days = ChronoUnit.DAYS.between(today, dueDateTime.toLocalDate())
    return when (days) {
        0L -> "오늘 ${dueDateTime.format(DateTimeFormatter.ofPattern("HH:mm"))}"
        1L -> "내일"
        else -> "${days}일 뒤"
    }
}
