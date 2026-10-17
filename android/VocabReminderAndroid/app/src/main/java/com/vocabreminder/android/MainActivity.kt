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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ReminderNotifications.ensureChannel(this)
        QuietHoursWorker.ensureScheduled(this)
        QuietHoursBoundaryScheduler.schedule(this)

        val initialCapture = captureFromIntent(intent)

        setContent {
            VocabReminderApp(
                initialText = initialCapture.text,
                initialMethod = initialCapture.method,
                initialSourceApp = initialCapture.sourceApp,
                initialSourceUrl = initialCapture.sourceUrl,
                initialSourceTitle = initialCapture.pageTitle
            )
        }
    }

    private fun captureFromIntent(intent: Intent?): InitialCapture {
        val provenance = CaptureProvenanceExtractor.fromIntent(
            intent = intent,
            callingPackage = callingPackage ?: callingActivity?.packageName,
            activityReferrer = referrer
        )
        return when (intent?.action) {
            Intent.ACTION_PROCESS_TEXT -> InitialCapture(
                text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString().orEmpty(),
                method = CaptureMethod.ANDROID_PROCESS_TEXT,
                sourceApp = provenance.sourceApp,
                sourceUrl = provenance.sourceUrl,
                pageTitle = provenance.pageTitle
            )
            Intent.ACTION_SEND -> InitialCapture(
                text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty(),
                method = CaptureMethod.ANDROID_SHARE,
                sourceApp = provenance.sourceApp,
                sourceUrl = provenance.sourceUrl,
                pageTitle = provenance.pageTitle
            )
            else -> InitialCapture()
        }
    }
}

private data class InitialCapture(
    val text: String = "",
    val method: CaptureMethod = CaptureMethod.MANUAL,
    val sourceApp: String = "",
    val sourceUrl: String = "",
    val pageTitle: String = ""
)

private enum class AppTab {
    USER,
    RECOMMENDED,
    SETTINGS
}

private const val POST_NOTIFICATIONS_PERMISSION = "android.permission.POST_NOTIFICATIONS"

private fun hasNotificationPermission(context: Context): Boolean {
    return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ActivityCompat.checkSelfPermission(context, POST_NOTIFICATIONS_PERMISSION) == PackageManager.PERMISSION_GRANTED
}

private fun notificationsAllowed(context: Context): Boolean {
    return hasNotificationPermission(context) && NotificationManagerCompat.from(context).areNotificationsEnabled()
}

private fun isBatteryOptimizationExempt(context: Context): Boolean {
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
    return powerManager.isIgnoringBatteryOptimizations(context.packageName)
}

private fun openNotificationSettings(context: Context) {
    val notificationIntent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    openSettingsIntent(context, notificationIntent)
}

private fun openBatterySettings(context: Context) {
    openSettingsIntent(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
}

private fun openSettingsIntent(context: Context, intent: Intent) {
    val fallback = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.parse("package:${context.packageName}")
    )
    runCatching {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.recoverCatching {
        context.startActivity(fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

private fun periodicSyncScheduled(context: Context): Boolean? {
    return runCatching {
        WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(SyncWorker.PERIODIC_WORK)
            .get(2, TimeUnit.SECONDS)
            .any { info ->
                info.state in setOf(
                    WorkInfo.State.ENQUEUED,
                    WorkInfo.State.RUNNING,
                    WorkInfo.State.BLOCKED
                )
            }
    }.getOrNull()
}

private fun finishExternalCaptureIfNeeded(context: Context, method: CaptureMethod) {
    if (method != CaptureMethod.MANUAL) {
        (context as? Activity)?.finish()
    }
}

private fun localSyncFile(context: Context): File {
    return File(context.getExternalFilesDir(null) ?: context.filesDir, "vocab-reminder-sync.json")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VocabReminderApp(
    initialText: String,
    initialMethod: CaptureMethod,
    initialSourceApp: String,
    initialSourceUrl: String,
    initialSourceTitle: String
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val store = remember { LocalVocabularyStore(context) }
    val initialCloudCredentials = remember { store.cloudSyncCredentials() }
    val initialOnboardingState = remember { store.onboardingState() }
    val initialNotificationsAllowed = remember { notificationsAllowed(context) }
    val shouldShowInitialCaptureGuide = initialText.isBlank() &&
        initialOnboardingState.captureGuideCompletedAt == null
    val cards = remember { mutableStateListOf<VocabularyCard>() }
    val contexts = remember { mutableStateListOf<StoredContext>() }
    val definitionCandidates = remember { mutableStateListOf<StoredDefinitionCandidate>() }
    val events = remember { mutableStateListOf<StoredReviewEvent>() }
    val weakWordIds = remember { mutableStateOf(emptySet<String>()) }
    var enrichmentStates by remember { mutableStateOf<Map<String, DictionaryEnrichmentState>>(emptyMap()) }

    var selectedTab by remember { mutableStateOf(AppTab.USER) }
    var showAddSheet by remember { mutableStateOf(initialText.isNotBlank()) }
    var showSyncSettings by remember { mutableStateOf(false) }
    var word by remember { mutableStateOf("") }
    var meaning by remember { mutableStateOf("") }
    var example by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var manualFieldsVisible by remember { mutableStateOf(false) }
    var pendingAnalysis by remember { mutableStateOf<CaptureAnalysis?>(null) }
    var preview by remember { mutableStateOf<DictionaryResult?>(null) }
    var previewMeaning by remember { mutableStateOf("") }
    var previewExample by remember { mutableStateOf("") }
    var captureMethod by remember { mutableStateOf(initialMethod) }
    var sourceApp by remember { mutableStateOf(initialSourceApp) }
    var sourceUrl by remember { mutableStateOf(initialSourceUrl) }
    var sourceTitle by remember { mutableStateOf(initialSourceTitle) }
    var settings by remember { mutableStateOf(store.settings()) }
    var syncServerUrl by remember { mutableStateOf(initialCloudCredentials.serverBaseUrl) }
    var syncBearerToken by remember { mutableStateOf(initialCloudCredentials.bearerToken) }
    var syncStatus by remember { mutableStateOf(store.cloudSyncStatus()) }
    var syncConnectionTestRunning by remember { mutableStateOf(false) }
    var cloudSyncRunning by remember { mutableStateOf(false) }
    var onboardingState by remember { mutableStateOf(initialOnboardingState) }
    var showCaptureGuide by remember { mutableStateOf(shouldShowInitialCaptureGuide) }
    var notificationsAreAllowed by remember { mutableStateOf(initialNotificationsAllowed) }
    var batteryOptimizationExempt by remember { mutableStateOf(isBatteryOptimizationExempt(context)) }
    var periodicWorkIsScheduled by remember { mutableStateOf<Boolean?>(null) }
    var readinessCheckedAt by remember { mutableStateOf(Instant.now()) }
    var showNotificationPermissionDialog by remember {
        mutableStateOf(
            initialText.isBlank() &&
                !shouldShowInitialCaptureGuide &&
                !initialNotificationsAllowed
        )
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notificationsAreAllowed = granted && notificationsAllowed(context)
        readinessCheckedAt = Instant.now()
        showNotificationPermissionDialog = false
    }
    val needsNotificationPermission = !notificationsAreAllowed
    val readinessItems = AndroidReadinessEvaluator.evaluate(
        AndroidReadinessInputs(
            notificationsAllowed = notificationsAreAllowed,
            batteryOptimizationExempt = batteryOptimizationExempt,
            periodicWorkScheduled = periodicWorkIsScheduled,
            firstLaunchAt = onboardingState.firstLaunchAt,
            lastWorkerRunAt = syncStatus.lastWorkerRunAt,
            lastSyncSuccessAt = syncStatus.lastSuccessAt,
            lastSyncFailureAt = syncStatus.lastFailureAt,
            lastSyncError = syncStatus.lastError,
            lastSyncRequestId = syncStatus.lastRequestId,
            now = readinessCheckedAt
        )
    )

    fun requestOrOpenNotificationSettings() {
        val runtimePermissionMissing = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !hasNotificationPermission(context)
        if (runtimePermissionMissing) {
            notificationPermissionLauncher.launch(POST_NOTIFICATIONS_PERMISSION)
        } else {
            showNotificationPermissionDialog = false
            openNotificationSettings(context)
        }
    }

    fun refreshDeviceReadiness() {
        notificationsAreAllowed = notificationsAllowed(context)
        batteryOptimizationExempt = isBatteryOptimizationExempt(context)
        syncStatus = store.cloudSyncStatus()
        readinessCheckedAt = Instant.now()
        scope.launch {
            periodicWorkIsScheduled = withContext(Dispatchers.IO) {
                periodicSyncScheduled(context)
            }
            readinessCheckedAt = Instant.now()
        }
    }

    fun repairAndRefreshBackgroundWork() {
        periodicWorkIsScheduled = null
        SyncWorker.ensureScheduled(context)
        SyncWorker.enqueueImmediate(context)
        refreshDeviceReadiness()
    }

    fun reload() {
        cards.clear()
        cards.addAll(store.cards().reversed())
        contexts.clear()
        contexts.addAll(store.contexts().reversed())
        definitionCandidates.clear()
        definitionCandidates.addAll(store.definitionCandidates().reversed())
        events.clear()
        events.addAll(store.events().reversed())
        enrichmentStates = store.dictionaryEnrichmentStates().associateBy { it.cardId }
        weakWordIds.value = store.weakWordIds()
        settings = store.settings()
    }

    fun showPreview(
        rawText: String,
        method: CaptureMethod,
        app: String,
        url: String = "",
        title: String = ""
    ) {
        val analysis = CaptureNormalizer.analyze(rawText)
        captureMethod = method
        sourceApp = app
        sourceUrl = CaptureProvenanceExtractor.sanitizeUrl(url)
        sourceTitle = title.trim().take(300)
        manualFieldsVisible = false
        preview = null
        pendingAnalysis = null
        if (analysis.normalizedText.isBlank()) {
            message = "저장할 텍스트를 입력하세요."
            return
        }
        if (analysis.needsWordSelection) {
            pendingAnalysis = analysis
            message = "문장이 들어왔습니다. 저장할 단어를 선택하세요."
            return
        }
        val result = DictionaryResolver.resolve(analysis, app, sourceUrl, sourceTitle)
        preview = result
        previewMeaning = result.meaningKo
        previewExample = result.sourceSentence.ifBlank { result.exampleSentence }
        message = if (result.meaningKo.isBlank()) "뜻을 찾지 못했습니다. 직접 입력하거나 일단 저장할 수 있습니다." else "저장 전 preview를 확인하세요."
    }

    fun startDirectInputFromPreview() {
        val result = preview ?: return
        word = result.displayWord.ifBlank { result.lemma }
        meaning = previewMeaning
        example = previewExample
        preview = null
        pendingAnalysis = null
        manualFieldsVisible = true
        message = ""
    }

    fun saveResolved(result: DictionaryResult, method: CaptureMethod): Boolean {
        val now = Instant.now()
        val schedulerSettings = store.settings().toSchedulerSettings()
        val dueAt = DueTimeJitter.apply(Scheduler.initialDueAt(now, schedulerSettings), now, schedulerSettings)
        val normalizedLemma = result.lemma.ifBlank { CaptureNormalizer.lemma(result.displayWord) }
        val existingBefore = store.cards().firstOrNull { it.lemma.equals(normalizedLemma, ignoreCase = true) }
        val editedExample = previewExample.trim()
        val defaultExample = result.sourceSentence.ifBlank { result.exampleSentence }.trim()
        val sourceSentence = when {
            existingBefore != null && result.sourceSentence.isBlank() && editedExample == defaultExample -> ""
            else -> editedExample.ifBlank { result.sourceSentence }
        }
        val finalResult = result.copy(
            meaningKo = previewMeaning.trim(),
            exampleSentence = editedExample,
            sourceSentence = sourceSentence,
            sourceType = if (previewMeaning.trim() != result.meaningKo.trim()) "user" else result.sourceType,
            sourceApp = sourceApp,
            sourceUrl = sourceUrl,
            sourceTitle = sourceTitle
        )
        val card = store.addResolvedCard(
            result = finalResult,
            captureMethod = method,
            dueAt = dueAt,
            now = now
        )
        if (card.meaningKo.isNotBlank()) {
            ReminderScheduler.schedule(context, card, now)
            message = if (existingBefore != null) {
                "${card.displayWord} 문맥 추가됨 · 카드 수 ${store.cards().size}개 유지"
            } else {
                "${card.displayWord} 저장됨 · 첫 알림까지 ${Duration.between(now, card.dueAt).toMinutes()}분"
            }
        } else {
            message = "${card.displayWord} 뜻 없이 저장됨 · 알림 보류"
        }
        word = ""
        meaning = ""
        example = ""
        manualFieldsVisible = false
        preview = null
        pendingAnalysis = null
        reload()
        SyncWorker.enqueueImmediate(context)
        finishExternalCaptureIfNeeded(context, method)
        return true
    }

    fun saveManual(): Boolean {
        val trimmedWord = word.trim()
        if (trimmedWord.isBlank()) {
            message = "단어를 입력하세요."
            return false
        }
        if (meaning.isBlank()) {
            showPreview(trimmedWord, CaptureMethod.MANUAL, "manual")
            return false
        }
        val now = Instant.now()
        val schedulerSettings = store.settings().toSchedulerSettings()
        val dueAt = DueTimeJitter.apply(Scheduler.initialDueAt(now, schedulerSettings), now, schedulerSettings)
        val card = store.addCard(
            displayWord = trimmedWord,
            meaningKo = meaning,
            exampleSentence = example,
            dueAt = dueAt,
            now = now,
            captureMethod = CaptureMethod.MANUAL,
            definitionSourcePrimary = "user"
        )
        if (card.meaningKo.isNotBlank()) {
            ReminderScheduler.schedule(context, card, now)
        }
        word = ""
        meaning = ""
        example = ""
        manualFieldsVisible = false
        message = "${card.displayWord} 저장됨 · 첫 알림까지 ${Duration.between(now, card.dueAt).toMinutes()}분"
        reload()
        SyncWorker.enqueueImmediate(context)
        return true
    }

    fun runCloudSync() {
        val serverUrl = syncServerUrl.trim()
        val token = syncBearerToken.trim()
        if (serverUrl.isBlank() || token.isBlank()) {
            message = syncMissingConfigurationMessage()
            return
        }
        store.saveCloudSyncCredentials(
            CloudSyncCredentials(
                serverBaseUrl = serverUrl,
                bearerToken = token
            )
        )
        cloudSyncRunning = true
        message = "동기화 중..."
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    store.syncWithCloud(CloudSyncClient(CloudSyncConfig(serverUrl, token)))
                }
            }
            cloudSyncRunning = false
            result.fold(
                onSuccess = { syncResult ->
                    store.recordCloudSyncSuccess(syncResult.skippedSummary, syncResult.requestId)
                    syncStatus = store.cloudSyncStatus()
                    message = if (syncResult.skippedSummary.isBlank()) {
                        "동기화 완료 · snapshot ${syncResult.snapshotJson.length}자"
                    } else {
                        "동기화 완료 · 스킵 ${syncResult.skippedSummary}"
                    }
                    reload()
                    ReminderNotifications.clearStalePrompt(context, store)
                    DailyReviewPlanner.planAndSchedule(context)
                },
                onFailure = { error ->
                    val errorMessage = error.message ?: error::class.java.simpleName
                    store.recordCloudSyncFailure(
                        errorMessage,
                        (error as? CloudSyncException)?.requestId
                    )
                    syncStatus = store.cloudSyncStatus()
                    message = "동기화 실패: $errorMessage"
                }
            )
        }
    }

    fun testSyncConnection() {
        val serverUrl = syncServerUrl.trim()
        val token = syncBearerToken.trim()
        if (serverUrl.isBlank() || token.isBlank()) {
            message = syncMissingConfigurationMessage()
            return
        }
        store.saveCloudSyncCredentials(
            CloudSyncCredentials(
                serverBaseUrl = serverUrl,
                bearerToken = token
            )
        )
        syncConnectionTestRunning = true
        message = "연결 테스트 중..."
        scope.launch {
            val client = CloudSyncClient(CloudSyncConfig(serverUrl, token))
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    client.health()
                }
            }
            syncConnectionTestRunning = false
            result.fold(
                onSuccess = { health ->
                    store.recordCloudSyncSuccess(requestId = client.lastRequestId)
                    syncStatus = store.cloudSyncStatus()
                    message = "동기화 연결 성공 · 카드 ${health.optInt("cards")}개"
                },
                onFailure = { error ->
                    val errorMessage = error.message ?: error::class.java.simpleName
                    store.recordCloudSyncFailure(
                        errorMessage,
                        (error as? CloudSyncException)?.requestId ?: client.lastRequestId
                    )
                    syncStatus = store.cloudSyncStatus()
                    message = "동기화 연결 실패: $errorMessage"
                }
            )
        }
    }

    fun exportLocalSync() {
        val payload = store.exportSyncBatchJson()
        val file = localSyncFile(context)
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(payload)
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("vocab-reminder-sync", payload))
        }.fold(
            onSuccess = {
                message = "Sync 내보내기 완료 · ${file.absolutePath}"
            },
            onFailure = { error ->
                message = "Sync 내보내기 실패: ${error.message ?: error::class.java.simpleName}"
            }
        )
    }

    fun importLocalSync() {
        val file = localSyncFile(context)
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clipboardText = clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
        val raw = when {
            file.exists() -> file.readText()
            clipboardText.trimStart().startsWith("{") && clipboardText.contains("\"cards\"") -> clipboardText
            else -> ""
        }
        if (raw.isBlank()) {
            message = "가져올 sync 파일 또는 클립보드 JSON이 없습니다."
            return
        }
        runCatching {
            store.importSyncBatchJson(raw)
            DailyReviewPlanner.planAndSchedule(context)
            QuietHoursBoundaryScheduler.schedule(context)
        }.fold(
            onSuccess = {
                reload()
                message = "Sync 가져오기 완료 · 카드 ${store.cards().size}개"
            },
            onFailure = { error ->
                message = "Sync 가져오기 실패: ${error.message ?: error::class.java.simpleName}"
            }
        )
    }

    fun deleteCard(card: VocabularyCard) {
        val deleted = store.softDeleteCard(card.id, Instant.now())
        if (deleted == null) {
            message = "${card.displayWord} 삭제 실패"
            return
        }
        ReminderScheduler.cancel(context, deleted)
        ReminderNotifications.cancel(context, deleted)
        reload()
        SyncWorker.enqueueImmediate(context)
        message = "${card.displayWord} 삭제됨"
    }

    fun saveCardEdit(card: VocabularyCard, editedMeaning: String, editedExample: String) {
        val result = store.updateCardDetails(
            cardId = card.id,
            meaningKo = editedMeaning,
            exampleSentence = editedExample
        )
        if (result == null) {
            message = "${card.displayWord} 수정 실패"
            return
        }
        if (result.filledMissingMeaning) {
            ReminderScheduler.schedule(context, result.after)
            ReminderNotifications.clearStalePrompt(context, store)
            DailyReviewPlanner.planAndSchedule(context)
        }
        reload()
        SyncWorker.enqueueImmediate(context)
        message = "${result.after.displayWord} 수정됨"
    }

    fun retryDictionaryEnrichment(card: VocabularyCard) {
        store.retryDictionaryEnrichment(card.id)
        reload()
        message = "${card.displayWord} 다시 해석 요청됨 · 캡처와 다른 기능은 계속 사용할 수 있습니다."
    }

    fun selectDefinitionCandidate(card: VocabularyCard, candidateId: String) {
        val result = store.selectDefinitionCandidate(card.id, candidateId)
        if (result == null) {
            message = "${card.displayWord} 뜻 후보 복원 실패"
            return
        }
        if (result.filledMissingMeaning) {
            ReminderScheduler.schedule(context, result.after)
            DailyReviewPlanner.planAndSchedule(context)
        }
        reload()
        SyncWorker.enqueueImmediate(context)
        message = "${result.after.displayWord} 뜻 후보를 현재 뜻으로 복원함"
    }

    fun reviewCardInApp(card: VocabularyCard, response: ReviewResponse) {
        val now = Instant.now()
        val schedulerSettings = store.settings().toSchedulerSettings()
        val result = DueTimeJitter.apply(
            Scheduler.applyReview(
                card = card.toSchedulerCard(),
                response = response,
                settings = schedulerSettings,
                now = now,
                recentResponses = store.recentResponses(card.id, 2),
                unavailableCountToday = store.unavailableCountToday(card.id, schedulerSettings.timezone, now),
                forgottenCountToday = store.forgottenCountToday(card.id, schedulerSettings.timezone, now)
            ),
            now,
            schedulerSettings
        )
        val updated = card.withSchedulerCard(result.card, now)
        store.updateCardAndAppendEvent(updated, result.event, now)
        ReminderNotifications.clearActivePrompt(context, card.id)
        ReminderNotifications.cancel(context, card)
        ReminderScheduler.schedule(context, updated, now)
        DailyReviewPlanner.planAndSchedule(context, now)
        reload()
        SyncWorker.enqueueImmediate(context)
        message = context.getString(
            when (response) {
                ReviewResponse.REMEMBERED -> R.string.card_review_remembered_message
                ReviewResponse.FORGOTTEN -> R.string.card_review_forgotten_message
                ReviewResponse.UNAVAILABLE -> R.string.card_review_unavailable_message
                ReviewResponse.SUSPEND -> R.string.card_review_suspended_message
                ReviewResponse.RESTORE -> R.string.card_review_restored_message
                else -> R.string.card_review_saved_message
            },
            updated.displayWord
        )
    }

    fun handleRecommendationInApp(card: VocabularyCard, action: String) {
        val now = Instant.now()
        when (action) {
            ReminderNotifications.RECOMMENDATION_START -> {
                store.markRecommendationStarted(card.id, now)
                store.findCard(card.id)?.let { ReminderScheduler.schedule(context, it, now) }
                message = context.getString(R.string.card_recommendation_started_message, card.displayWord)
            }
            ReminderNotifications.RECOMMENDATION_KNOWN -> {
                store.softDeleteRecommendation(card.id, "known", now)
                message = context.getString(R.string.card_recommendation_known_message, card.displayWord)
            }
            ReminderNotifications.RECOMMENDATION_DISMISS -> {
                store.softDeleteRecommendation(card.id, "dismissed", now)
                message = context.getString(R.string.card_recommendation_dismissed_message, card.displayWord)
            }
            else -> return
        }
        ReminderNotifications.clearActivePrompt(context, card.id)
        ReminderNotifications.cancel(context, card)
        DailyReviewPlanner.planAndSchedule(context, now)
        reload()
        SyncWorker.enqueueImmediate(context)
    }

    LaunchedEffect(Unit) {
        DailyReviewPlanner.planAndSchedule(context)
        reload()
        SyncWorker.ensureScheduled(context)
        SyncWorker.enqueueImmediate(context)
        refreshDeviceReadiness()
        if (initialText.isNotBlank()) {
            showPreview(
                initialText,
                initialMethod,
                initialSourceApp,
                initialSourceUrl,
                initialSourceTitle
            )
        }
    }

    DisposableEffect(lifecycleOwner, store) {
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                reload()
                refreshDeviceReadiness()
            }
        }
        val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (store.isUiRefreshKey(key)) {
                scope.launch {
                    reload()
                    syncStatus = store.cloudSyncStatus()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)
        store.registerChangeListener(prefsListener)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
            store.unregisterChangeListener(prefsListener)
        }
    }

    if (showCaptureGuide) {
        AlertDialog(
            onDismissRequest = {
                showCaptureGuide = false
                showNotificationPermissionDialog = needsNotificationPermission
            },
            title = { Text("30초 선택·공유 캡처 안내") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("1. 브라우저나 문서에서 단어를 길게 눌러 선택하고, 더보기의 ‘Vocabulary Reminder’ 또는 ‘텍스트 처리’를 누르세요.")
                    Text("2. 문장이나 링크는 공유 메뉴에서 Vocabulary Reminder를 선택한 뒤 저장할 단어를 고르면 됩니다.")
                    Text("3. 뜻을 바로 못 찾아도 캡처는 즉시 저장됩니다. 뜻은 백그라운드에서 보강되고, 캡처 출처도 함께 남습니다.")
                }
            },
            confirmButton = {
                TextButton(
                    modifier = Modifier.testTag("complete_capture_guide"),
                    onClick = {
                        onboardingState = store.completeCaptureGuide()
                        showCaptureGuide = false
                        showNotificationPermissionDialog = needsNotificationPermission
                    }
                ) {
                    Text("안내 완료")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showCaptureGuide = false
                        showNotificationPermissionDialog = needsNotificationPermission
                    }
                ) {
                    Text("나중에")
                }
            }
        )
    } else if (showNotificationPermissionDialog && needsNotificationPermission) {
        AlertDialog(
            onDismissRequest = { showNotificationPermissionDialog = false },
            title = { Text("복습 알림 권한이 필요합니다") },
            text = {
                Text("Vocabulary Reminder는 저장한 단어를 알림으로 다시 묻는 앱입니다. 알림을 허용해야 10분 뒤 복습, 못외웠다 재확인, 나중에 하기 흐름을 받을 수 있습니다.")
            },
            confirmButton = {
                TextButton(onClick = { requestOrOpenNotificationSettings() }) {
                    Text(
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            !hasNotificationPermission(context)
                        ) {
                            "권한 요청"
                        } else {
                            "알림 설정 열기"
                        }
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showNotificationPermissionDialog = false }) {
                    Text("나중에")
                }
            }
        )
    }

    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            bottomBar = {
                AppNavigationBar(
                    selectedTab = selectedTab,
                    onTabSelected = { tab ->
                        selectedTab = tab
                        if (tab == AppTab.RECOMMENDED) {
                            DailyReviewPlanner.planAndSchedule(context)
                            reload()
                        }
                        if (tab != AppTab.SETTINGS) {
                            showSyncSettings = false
                        }
                    }
                )
            },
            floatingActionButton = {
                if (selectedTab == AppTab.USER) {
                    FloatingActionButton(
                        modifier = Modifier.testTag("add_word_fab"),
                        onClick = {
                            captureMethod = CaptureMethod.MANUAL
                            sourceApp = "manual"
                            sourceUrl = ""
                            sourceTitle = ""
                            manualFieldsVisible = false
                            showAddSheet = true
                        }
                    ) {
                        Text("+")
                    }
                }
            }
        ) { innerPadding ->
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                when (selectedTab) {
                    AppTab.USER -> UserWordbookScreen(
                        cards = cards.filter { it.wordbook == Wordbook.USER },
                        contexts = contexts,
                        definitionCandidates = definitionCandidates,
                        events = events,
                        enrichmentStates = enrichmentStates,
                        message = message,
                        weakWordIds = weakWordIds.value,
                        timezone = settings.timezone,
                        needsNotificationPermission = needsNotificationPermission,
                        onRequestNotificationPermission = { showNotificationPermissionDialog = true },
                        onEditSave = { card, editedMeaning, editedExample -> saveCardEdit(card, editedMeaning, editedExample) },
                        onRetryEnrichment = { retryDictionaryEnrichment(it) },
                        onSelectDefinitionCandidate = { card, candidateId ->
                            selectDefinitionCandidate(card, candidateId)
                        },
                        onReview = { card, response -> reviewCardInApp(card, response) },
                        onDelete = { deleteCard(it) }
                    )
                    AppTab.RECOMMENDED -> RecommendationWordbookScreen(
                        cards = cards.filter { it.wordbook == Wordbook.RECOMMENDED },
                        recommendationStatus = com.vocabreminder.android.recommendation.RecommendationStatus.describe(store),
                        contexts = contexts,
                        definitionCandidates = definitionCandidates,
                        events = events,
                        enrichmentStates = enrichmentStates,
                        message = message,
                        weakWordIds = weakWordIds.value,
                        timezone = settings.timezone,
                        onEditSave = { card, editedMeaning, editedExample -> saveCardEdit(card, editedMeaning, editedExample) },
                        onRetryEnrichment = { retryDictionaryEnrichment(it) },
                        onSelectDefinitionCandidate = { card, candidateId ->
                            selectDefinitionCandidate(card, candidateId)
                        },
                        onReview = { card, response -> reviewCardInApp(card, response) },
                        onRecommendationAction = { card, action -> handleRecommendationInApp(card, action) },
                        onDelete = { deleteCard(it) }
                    )
                    AppTab.SETTINGS -> SettingsScreen(
                        settings = settings,
                        readinessItems = readinessItems,
                        showSyncSettings = showSyncSettings,
                        message = message,
                        onSettingsChange = { settings = it },
                        onSaveSettings = {
                            store.saveSettings(settings)
                            val summary = DailyReviewPlanner.planAndSchedule(context)
                            QuietHoursBoundaryScheduler.schedule(context)
                            val intro = if (summary.recommendationIntroShown) " · 추천 안내 표시" else ""
                            message = "설정 저장됨 · 알림 ${summary.scheduledNotifications}개 편성$intro"
                            reload()
                        },
                        onPlan = {
                            val summary = DailyReviewPlanner.planAndSchedule(context)
                            QuietHoursBoundaryScheduler.schedule(context)
                            val intro = if (summary.recommendationIntroShown) ", 추천 안내 표시" else ""
                            message = "오늘 편성: 추천 ${summary.generatedRecommendations}, 알림 ${summary.scheduledNotifications}, 재조정 ${summary.rebalancedCards}$intro"
                            reload()
                        },
                        onRebalance = {
                            val count = DailyReviewPlanner.rebalanceDueCards(context)
                            message = "복습 재조정 ${count}개"
                            reload()
                        },
                        onOpenNotificationSettings = { openNotificationSettings(context) },
                        onOpenBatterySettings = { openBatterySettings(context) },
                        onRefreshReadiness = { refreshDeviceReadiness() },
                        onRepairBackgroundWork = { repairAndRefreshBackgroundWork() },
                        onShowCaptureGuide = {
                            showNotificationPermissionDialog = false
                            showCaptureGuide = true
                        },
                        onOpenSync = {
                            syncStatus = store.cloudSyncStatus()
                            showSyncSettings = true
                        },
                        onBackFromSync = { showSyncSettings = false },
                        syncContent = {
                            CloudSyncPanel(
                                serverUrl = syncServerUrl,
                                authToken = syncBearerToken,
                                status = syncStatus,
                                connectionTestRunning = syncConnectionTestRunning,
                                running = cloudSyncRunning,
                                onServerUrlChange = { syncServerUrl = it },
                                onAuthTokenChange = { syncBearerToken = it },
                                onTestConnection = { testSyncConnection() },
                                onSync = { runCloudSync() },
                                onLocalExport = { exportLocalSync() },
                                onLocalImport = { importLocalSync() }
                            )
                        }
                    )
                }
            }
        }

        if (showAddSheet) {
            ModalBottomSheet(onDismissRequest = { showAddSheet = false }) {
                AddWordSheetContent(
                    word = word,
                    meaning = meaning,
                    example = example,
                    message = message,
                    manualFieldsVisible = manualFieldsVisible,
                    pendingAnalysis = pendingAnalysis,
                    preview = preview,
                    previewMeaning = previewMeaning,
                    previewExample = previewExample,
                    onWordChange = { word = it },
                    onMeaningChange = { meaning = it },
                    onExampleChange = { example = it },
                    onPreview = { showPreview(word, CaptureMethod.MANUAL, "manual") },
                    onShowManualFields = { manualFieldsVisible = true },
                    onDirectInputFromPreview = { startDirectInputFromPreview() },
                    onClipboard = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                        showPreview(text, CaptureMethod.MANUAL, "clipboard")
                    },
                    onSaveManual = {
                        if (saveManual()) {
                            showAddSheet = false
                        }
                    },
                    onCandidateSelected = { analysis, selected ->
                        val selectedAnalysis = CaptureNormalizer.analyzeSelection(analysis.rawText, selected)
                        val result = DictionaryResolver.resolve(
                            selectedAnalysis,
                            sourceApp,
                            sourceUrl,
                            sourceTitle
                        )
                        preview = result
                        previewMeaning = result.meaningKo
                        previewExample = selectedAnalysis.sourceSentence
                        pendingAnalysis = null
                        manualFieldsVisible = false
                        message = "저장 전 preview를 확인하세요."
                    },
                    onPreviewMeaningChange = { previewMeaning = it },
                    onPreviewExampleChange = { previewExample = it },
                    onSavePreview = { result ->
                        if (saveResolved(result, captureMethod)) {
                            showAddSheet = false
                        }
                    },
                    onCancelPreview = {
                        preview = null
                        pendingAnalysis = null
                        manualFieldsVisible = false
                        message = "취소됨"
                        showAddSheet = false
                    }
                )
            }
        }
    }
}

@Composable
private fun AppNavigationBar(selectedTab: AppTab, onTabSelected: (AppTab) -> Unit) {
    NavigationBar {
        NavigationBarItem(
            modifier = Modifier.testTag("tab_user_words"),
            selected = selectedTab == AppTab.USER,
            onClick = { onTabSelected(AppTab.USER) },
            label = { Text(stringResource(R.string.tab_user_words)) },
            icon = { Text("내") }
        )
        NavigationBarItem(
            modifier = Modifier.testTag("tab_recommendations"),
            selected = selectedTab == AppTab.RECOMMENDED,
            onClick = { onTabSelected(AppTab.RECOMMENDED) },
            label = { Text(stringResource(R.string.tab_recommendations)) },
            icon = { Text("추") }
        )
        NavigationBarItem(
            modifier = Modifier.testTag("tab_settings"),
            selected = selectedTab == AppTab.SETTINGS,
            onClick = { onTabSelected(AppTab.SETTINGS) },
            label = { Text(stringResource(R.string.tab_settings)) },
            icon = { Text("설") }
        )
    }
}

@Composable
private fun UserWordbookScreen(
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
private fun RecommendationWordbookScreen(
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
private fun WordbookScreen(
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
private fun NotificationPermissionCard(onRequestNotificationPermission: () -> Unit) {
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
private fun AndroidReadinessPanel(
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
private fun SettingsScreen(
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
private fun AddWordSheetContent(
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
private fun CloudSyncPanel(
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
private fun AddWordForm(
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
private fun StatusSnackbar(
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

@Composable
private fun CandidateSelection(
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
private fun SavePreview(
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
private fun PreviewInfoRow(label: String, value: String) {
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
private fun SourceLabel(label: String) {
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

@Composable
private fun SettingsPanel(
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
private fun NumberField(label: String, value: String, onChange: (String) -> Unit) {
    SettingsTextField(label = label, value = value, error = "", onChange = onChange)
}

@Composable
private fun TimeField(label: String, value: String, error: String, onChange: (String) -> Unit) {
    SettingsTextField(label = label, value = value, error = error, onChange = onChange)
}

@Composable
private fun SettingsTextField(label: String, value: String, error: String, onChange: (String) -> Unit) {
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
private fun SettingsActionButton(label: String, onClick: () -> Unit) {
    Button(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        onClick = onClick
    ) {
        Text(label, maxLines = 1)
    }
}
