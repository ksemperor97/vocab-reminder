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
internal fun AndroidReadinessPanel(
    items: List<AndroidReadinessItem>,
    onOpenNotificationSettings: () -> Unit,
    onOpenBatterySettings: () -> Unit,
    onRefreshReadiness: () -> Unit,
    onRepairBackgroundWork: () -> Unit,
    onOpenSync: () -> Unit,
    onShowCaptureGuide: () -> Unit
) {
    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("앱 상태 점검", style = MaterialTheme.typography.titleMedium)
            Text(
                "알림·배터리·백그라운드 작업·동기화를 각각 확인합니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            items.forEach { item ->
                Surface(
                    modifier = Modifier.testTag("readiness_${item.kind.name.lowercase()}"),
                    color = when (item.level) {
                        AndroidReadinessLevel.READY -> MaterialTheme.colorScheme.secondaryContainer
                        AndroidReadinessLevel.CHECKING -> MaterialTheme.colorScheme.surfaceVariant
                        AndroidReadinessLevel.ATTENTION -> MaterialTheme.colorScheme.errorContainer
                    },
                    shape = MaterialTheme.shapes.small
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = when (item.level) {
                                AndroidReadinessLevel.READY -> "정상 · ${item.title}"
                                AndroidReadinessLevel.CHECKING -> "확인 중 · ${item.title}"
                                AndroidReadinessLevel.ATTENTION -> "확인 필요 · ${item.title}"
                            },
                            style = MaterialTheme.typography.labelLarge
                        )
                        Text(item.detail, style = MaterialTheme.typography.bodySmall)
                        when {
                            item.kind == AndroidReadinessKind.NOTIFICATIONS &&
                                item.level == AndroidReadinessLevel.ATTENTION -> {
                                TextButton(
                                    modifier = Modifier.testTag("open_notification_settings"),
                                    onClick = onOpenNotificationSettings
                                ) {
                                    Text("알림 설정 열기")
                                }
                            }
                            item.kind == AndroidReadinessKind.BATTERY &&
                                item.level == AndroidReadinessLevel.ATTENTION -> {
                                TextButton(
                                    modifier = Modifier.testTag("open_battery_settings"),
                                    onClick = onOpenBatterySettings
                                ) {
                                    Text("배터리 설정 열기")
                                }
                            }
                            item.kind == AndroidReadinessKind.WORK_MANAGER &&
                                item.level != AndroidReadinessLevel.READY -> {
                                TextButton(onClick = onRepairBackgroundWork) {
                                    Text("백그라운드 작업 재등록·점검")
                                }
                            }
                            item.kind == AndroidReadinessKind.SYNC &&
                                item.level == AndroidReadinessLevel.ATTENTION -> {
                                TextButton(onClick = onOpenSync) {
                                    Text("동기화 상태 열기")
                                }
                            }
                        }
                    }
                }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    TextButton(onClick = onRefreshReadiness) {
                        Text("다시 점검")
                    }
                }
                item {
                    TextButton(
                        modifier = Modifier.testTag("show_capture_guide"),
                        onClick = onShowCaptureGuide
                    ) {
                        Text("캡처 안내 다시 보기")
                    }
                }
            }
        }
    }
}

@Composable
internal fun SettingsScreen(
    settings: UserSettingsData,
    readinessItems: List<AndroidReadinessItem>,
    showSyncSettings: Boolean,
    message: String,
    onSettingsChange: (UserSettingsData) -> Unit,
    onSaveSettings: () -> Unit,
    onPlan: () -> Unit,
    onRebalance: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onOpenBatterySettings: () -> Unit,
    onRefreshReadiness: () -> Unit,
    onRepairBackgroundWork: () -> Unit,
    onShowCaptureGuide: () -> Unit,
    onOpenSync: () -> Unit,
    onBackFromSync: () -> Unit,
    syncContent: @Composable () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .testTag("settings_list")
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(stringResource(R.string.tab_settings), style = MaterialTheme.typography.headlineSmall)
        }
        if (showSyncSettings) {
            item {
                TextButton(
                    modifier = Modifier.testTag("sync_back_button"),
                    onClick = onBackFromSync
                ) {
                    Text("설정으로 돌아가기")
                }
            }
            item {
                syncContent()
            }
        } else {
            item {
                AndroidReadinessPanel(
                    items = readinessItems,
                    onOpenNotificationSettings = onOpenNotificationSettings,
                    onOpenBatterySettings = onOpenBatterySettings,
                    onRefreshReadiness = onRefreshReadiness,
                    onRepairBackgroundWork = onRepairBackgroundWork,
                    onOpenSync = onOpenSync,
                    onShowCaptureGuide = onShowCaptureGuide
                )
            }
            item {
                SettingsPanel(
                    settings = settings,
                    onSettingsChange = onSettingsChange,
                    onSave = onSaveSettings,
                    onPlan = onPlan,
                    onRebalance = onRebalance
                )
            }
            item {
                Button(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("open_sync_settings_button"),
                    onClick = onOpenSync
                ) {
                    Text(stringResource(R.string.sync_settings_title))
                }
            }
            if (message.isNotBlank()) {
                item {
                    StatusSnackbar(message = message)
                }
            }
        }
    }
}

@Composable
internal fun CloudSyncPanel(
    serverUrl: String,
    authToken: String,
    status: CloudSyncStatus,
    connectionTestRunning: Boolean,
    running: Boolean,
    onServerUrlChange: (String) -> Unit,
    onAuthTokenChange: (String) -> Unit,
    onTestConnection: () -> Unit,
    onSync: () -> Unit,
    onLocalExport: () -> Unit,
    onLocalImport: () -> Unit
) {
    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(stringResource(R.string.sync_settings_title), style = MaterialTheme.typography.titleMedium)
            Text(
                text = cloudSyncStatusText(serverUrl, authToken),
                style = MaterialTheme.typography.bodyMedium
            )
            Text("마지막 성공: ${status.lastSuccessAt?.toString() ?: "없음"}", style = MaterialTheme.typography.bodySmall)
            if (status.lastSkippedSummary.isNotBlank()) {
                Text("스킵: ${status.lastSkippedSummary}", style = MaterialTheme.typography.bodySmall)
            }
            if (status.lastError.isNotBlank()) {
                val requestSuffix = shortRequestId(status.lastRequestId)
                    ?.let { " · 요청 #$it" }
                    .orEmpty()
                Text(
                    "마지막 오류: ${status.lastError}$requestSuffix",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            DebugCloudSyncControls(
                serverUrl = serverUrl,
                authToken = authToken,
                onServerUrlChange = onServerUrlChange,
                onAuthTokenChange = onAuthTokenChange,
                onLocalExport = onLocalExport,
                onLocalImport = onLocalImport
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    modifier = Modifier.testTag("sync_connection_test_button"),
                    enabled = !connectionTestRunning,
                    onClick = onTestConnection
                ) {
                    Text(if (connectionTestRunning) "확인 중" else "연결 테스트")
                }
                Button(
                    modifier = Modifier.testTag("cloud_sync_button"),
                    enabled = !running,
                    onClick = onSync
                ) {
                    Text(if (running) "동기화 중" else "지금 동기화")
                }
            }
        }
    }
}

@Composable
internal fun SettingsPanel(
    settings: UserSettingsData,
    onSettingsChange: (UserSettingsData) -> Unit,
    onSave: () -> Unit,
    onPlan: () -> Unit,
    onRebalance: () -> Unit
) {
    var showWatchReplyHelp by remember { mutableStateOf(false) }
    var quietStartError by remember { mutableStateOf("") }
    var quietEndError by remember { mutableStateOf("") }
    var timezoneError by remember { mutableStateOf("") }
    val timeErrorText = stringResource(R.string.settings_time_error)
    val timezoneErrorText = stringResource(R.string.settings_timezone_error)
    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.titleMedium)
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                NumberField(stringResource(R.string.settings_daily_goal_label), settings.dailyWordGoal.toString()) { raw ->
                    raw.toIntOrNull()?.takeIf { it >= 1 }?.let {
                        onSettingsChange(settings.copy(dailyWordGoal = it))
                    }
                }
                NumberField(stringResource(R.string.settings_queue_cap_label), settings.maxQueueSize.toString()) { raw ->
                    raw.toIntOrNull()?.takeIf { it >= 1 }?.let {
                        onSettingsChange(settings.copy(maxQueueSize = it))
                    }
                }
                NumberField(stringResource(R.string.settings_snooze_label), settings.snoozeDurationMinutes.toString()) { raw ->
                    raw.toLongOrNull()?.takeIf { it >= 1 }?.let {
                        onSettingsChange(settings.copy(snoozeDurationMinutes = it))
                    }
                }
                TimeField(
                    label = stringResource(R.string.settings_quiet_start_label),
                    value = settings.quietHoursStart.toString(),
                    error = quietStartError
                ) { raw ->
                    runCatching { LocalTime.parse(raw) }.fold(
                        onSuccess = { time ->
                            quietStartError = ""
                            onSettingsChange(settings.copy(quietHoursStart = time))
                        },
                        onFailure = { quietStartError = timeErrorText }
                    )
                }
                TimeField(
                    label = stringResource(R.string.settings_quiet_end_label),
                    value = settings.quietHoursEnd.toString(),
                    error = quietEndError
                ) { raw ->
                    runCatching { LocalTime.parse(raw) }.fold(
                        onSuccess = { time ->
                            quietEndError = ""
                            onSettingsChange(settings.copy(quietHoursEnd = time))
                        },
                        onFailure = { quietEndError = timeErrorText }
                    )
                }
                TimeField(
                    label = stringResource(R.string.settings_timezone_label),
                    value = settings.timezone.id,
                    error = timezoneError
                ) { raw ->
                    runCatching { ZoneId.of(raw) }.fold(
                        onSuccess = { zone ->
                            timezoneError = ""
                            onSettingsChange(settings.copy(timezone = zone))
                        },
                        onFailure = { timezoneError = timezoneErrorText }
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.settings_recommendations_label))
                Switch(
                    checked = settings.enableWordRecommendations,
                    onCheckedChange = { onSettingsChange(settings.copy(enableWordRecommendations = it)) }
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.settings_watch_reply_label))
                Switch(
                    checked = settings.enableWatchReplyReview,
                    onCheckedChange = { onSettingsChange(settings.copy(enableWatchReplyReview = it)) }
                )
            }
            TextButton(onClick = { showWatchReplyHelp = !showWatchReplyHelp }) {
                Text(stringResource(R.string.settings_watch_reply_help_button))
            }
            if (showWatchReplyHelp) {
                Text(
                    text = stringResource(R.string.settings_watch_reply_help),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingsActionButton(stringResource(R.string.settings_save_button), onSave)
                SettingsActionButton(stringResource(R.string.settings_plan_today_button), onPlan)
                SettingsActionButton(stringResource(R.string.settings_rebalance_button), onRebalance)
            }
        }
    }
}

@Composable
internal fun NumberField(label: String, value: String, onChange: (String) -> Unit) {
    SettingsTextField(label = label, value = value, error = "", onChange = onChange)
}

@Composable
internal fun TimeField(label: String, value: String, error: String, onChange: (String) -> Unit) {
    SettingsTextField(label = label, value = value, error = error, onChange = onChange)
}

@Composable
internal fun SettingsTextField(label: String, value: String, error: String, onChange: (String) -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = label,
            maxLines = 1,
            style = MaterialTheme.typography.labelLarge
        )
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = value,
            onValueChange = onChange,
            isError = error.isNotBlank(),
            singleLine = true
        )
        if (error.isNotBlank()) {
            Text(
                text = error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
internal fun SettingsActionButton(label: String, onClick: () -> Unit) {
    Button(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        onClick = onClick
    ) {
        Text(label, maxLines = 1)
    }
}
