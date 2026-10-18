package com.vocabreminder.android

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vocabreminder.android.capture.CaptureAnalysis
import com.vocabreminder.android.capture.CaptureMethod
import com.vocabreminder.android.capture.CaptureNormalizer
import com.vocabreminder.android.capture.CaptureProvenanceExtractor
import com.vocabreminder.android.data.AndroidReadinessEvaluator
import com.vocabreminder.android.data.AndroidReadinessInputs
import com.vocabreminder.android.data.AndroidReadinessItem
import com.vocabreminder.android.data.CloudSyncCredentials
import com.vocabreminder.android.data.DictionaryEnrichmentState
import com.vocabreminder.android.data.LocalVocabularyStore
import com.vocabreminder.android.data.StoreChanges
import com.vocabreminder.android.data.StoredContext
import com.vocabreminder.android.data.StoredDefinitionCandidate
import com.vocabreminder.android.data.StoredReviewEvent
import com.vocabreminder.android.data.UserSettingsData
import com.vocabreminder.android.data.VocabularyCard
import com.vocabreminder.android.data.dictionaryEnrichmentStates
import com.vocabreminder.android.data.retryDictionaryEnrichment
import com.vocabreminder.android.dictionary.DictionaryResolver
import com.vocabreminder.android.dictionary.DictionaryResult
import com.vocabreminder.android.notifications.DailyReviewPlanner
import com.vocabreminder.android.notifications.QuietHoursBoundaryScheduler
import com.vocabreminder.android.notifications.ReminderNotifications
import com.vocabreminder.android.notifications.ReminderScheduler
import com.vocabreminder.android.recommendation.RecommendationStatus
import com.vocabreminder.android.scheduler.DueTimeJitter
import com.vocabreminder.android.scheduler.ReviewResponse
import com.vocabreminder.android.scheduler.Scheduler
import com.vocabreminder.android.sync.CloudSyncClient
import com.vocabreminder.android.sync.CloudSyncConfig
import com.vocabreminder.android.sync.CloudSyncException
import com.vocabreminder.android.sync.SyncCoordinator
import com.vocabreminder.android.sync.SyncWorker
import com.vocabreminder.android.update.AppRelease
import com.vocabreminder.android.update.AppUpdateClient
import com.vocabreminder.android.update.AppUpdateException
import com.vocabreminder.android.update.AppUpdateInstaller
import com.vocabreminder.android.update.AppUpdatePolicy
import com.vocabreminder.android.update.AppUpdateState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Duration
import java.time.Instant

/** Everything the wordbook and settings screens render, read from the store in one pass. */
internal data class LibraryState(
    val cards: List<VocabularyCard> = emptyList(),
    val contexts: List<StoredContext> = emptyList(),
    val definitionCandidates: List<StoredDefinitionCandidate> = emptyList(),
    val events: List<StoredReviewEvent> = emptyList(),
    val enrichmentStates: Map<String, DictionaryEnrichmentState> = emptyMap(),
    val weakWordIds: Set<String> = emptySet(),
    val recommendationStatus: String = ""
)

/** The add-word sheet: raw capture, word selection, dictionary preview, manual fields. */
internal class CaptureState {
    var showSheet by mutableStateOf(false)
    var word by mutableStateOf("")
    var meaning by mutableStateOf("")
    var example by mutableStateOf("")
    var manualFieldsVisible by mutableStateOf(false)
    var pendingAnalysis by mutableStateOf<CaptureAnalysis?>(null)
    var preview by mutableStateOf<DictionaryResult?>(null)
    var previewMeaning by mutableStateOf("")
    var previewExample by mutableStateOf("")
    var method by mutableStateOf(CaptureMethod.MANUAL)
    var sourceApp by mutableStateOf("")
    var sourceUrl by mutableStateOf("")
    var sourceTitle by mutableStateOf("")

    fun clearForm() {
        word = ""
        meaning = ""
        example = ""
        manualFieldsVisible = false
        preview = null
        pendingAnalysis = null
    }
}

internal data class InitialCapture(
    val text: String = "",
    val method: CaptureMethod = CaptureMethod.MANUAL,
    val sourceApp: String = "",
    val sourceUrl: String = "",
    val pageTitle: String = ""
)

/**
 * Screen state and user actions for the single-activity app. Composables only
 * render this state and forward events; the store, scheduler, notifications and
 * sync are driven from here.
 */
internal class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val context: Context get() = getApplication()
    val store = LocalVocabularyStore(application)

    var library by mutableStateOf(LibraryState())
        private set
    var settings by mutableStateOf(store.settings())
    var message by mutableStateOf("")
    val capture = CaptureState()

    private val initialCredentials = store.cloudSyncCredentials()
    var syncServerUrl by mutableStateOf(initialCredentials.serverBaseUrl)
    var syncBearerToken by mutableStateOf(initialCredentials.bearerToken)
    var syncStatus by mutableStateOf(store.cloudSyncStatus())
        private set
    var cloudSyncRunning by mutableStateOf(false)
        private set
    var syncConnectionTestRunning by mutableStateOf(false)
        private set
    var onboarding by mutableStateOf(store.onboardingState())
        private set

    var notificationsAreAllowed by mutableStateOf(notificationsAllowed(application))
        private set
    private var batteryOptimizationExempt by mutableStateOf(isBatteryOptimizationExempt(application))
    private var periodicWorkIsScheduled by mutableStateOf<Boolean?>(null)
    private var readinessCheckedAt by mutableStateOf(Instant.now())

    /** Set when an external capture (share / text selection) is saved and the activity should close. */
    var finishRequested by mutableStateOf(false)
        private set

    private var initialCaptureHandled = false

    var appUpdate by mutableStateOf<AppUpdateState>(
        if (AppUpdateInstaller.enabled) AppUpdateState.Idle else AppUpdateState.Disabled
    )
        private set
    var showInstallPermissionDialog by mutableStateOf(false)
        private set
    val installedVersionLabel: String = runCatching {
        "${AppUpdateInstaller.installedVersionName(application)} (${AppUpdateInstaller.installedVersionCode(application)})"
    }.getOrDefault(BuildConfig.VERSION_NAME)

    init {
        reload()
        // Workers, notification actions and sync write from other threads; follow them.
        viewModelScope.launch {
            StoreChanges.revision.drop(1).collect {
                reload()
                syncStatus = store.cloudSyncStatus()
            }
        }
        if (AppUpdateInstaller.enabled) {
            viewModelScope.launch {
                AppUpdateInstaller.installOutcomes.collect(::onInstallOutcome)
            }
        }
    }

    val readinessItems: List<AndroidReadinessItem>
        get() = AndroidReadinessEvaluator.evaluate(
            AndroidReadinessInputs(
                notificationsAllowed = notificationsAreAllowed,
                batteryOptimizationExempt = batteryOptimizationExempt,
                periodicWorkScheduled = periodicWorkIsScheduled,
                firstLaunchAt = onboarding.firstLaunchAt,
                lastWorkerRunAt = syncStatus.lastWorkerRunAt,
                lastSyncSuccessAt = syncStatus.lastSuccessAt,
                lastSyncFailureAt = syncStatus.lastFailureAt,
                lastSyncError = syncStatus.lastError,
                lastSyncRequestId = syncStatus.lastRequestId,
                now = readinessCheckedAt
            )
        )

    fun onStart(initial: InitialCapture) {
        DailyReviewPlanner.planAndSchedule(context)
        reload()
        SyncWorker.ensureScheduled(context)
        SyncWorker.enqueueImmediate(context)
        refreshDeviceReadiness()
        // Only on the first start of this screen, not when it is recreated mid-download.
        if (appUpdate == AppUpdateState.Idle && initial.text.isBlank()) {
            AppUpdateInstaller.cleanupDownloads(context)
            checkForUpdate()
        }
        if (!initialCaptureHandled && initial.text.isNotBlank()) {
            initialCaptureHandled = true
            capture.showSheet = true
            showPreview(initial.text, initial.method, initial.sourceApp, initial.sourceUrl, initial.pageTitle)
        }
    }

    fun reload() {
        library = LibraryState(
            cards = store.cards().reversed(),
            contexts = store.contexts().reversed(),
            definitionCandidates = store.definitionCandidates().reversed(),
            events = store.events().reversed(),
            enrichmentStates = store.dictionaryEnrichmentStates().associateBy { it.cardId },
            weakWordIds = store.weakWordIds(),
            recommendationStatus = RecommendationStatus.describe(store)
        )
        settings = store.settings()
    }

    // ---- Device readiness -----------------------------------------------------------------

    fun refreshDeviceReadiness() {
        notificationsAreAllowed = notificationsAllowed(context)
        batteryOptimizationExempt = isBatteryOptimizationExempt(context)
        syncStatus = store.cloudSyncStatus()
        readinessCheckedAt = Instant.now()
        viewModelScope.launch {
            periodicWorkIsScheduled = withContext(Dispatchers.IO) { periodicSyncScheduled(context) }
            readinessCheckedAt = Instant.now()
        }
    }

    fun onNotificationPermissionResult(granted: Boolean) {
        notificationsAreAllowed = granted && notificationsAllowed(context)
        readinessCheckedAt = Instant.now()
    }

    fun repairBackgroundWork() {
        periodicWorkIsScheduled = null
        SyncWorker.ensureScheduled(context)
        SyncWorker.enqueueImmediate(context)
        refreshDeviceReadiness()
    }

    fun completeCaptureGuide() {
        onboarding = store.completeCaptureGuide()
    }

    // ---- Capture ---------------------------------------------------------------------------

    fun openManualCapture() {
        capture.method = CaptureMethod.MANUAL
        capture.sourceApp = "manual"
        capture.sourceUrl = ""
        capture.sourceTitle = ""
        capture.manualFieldsVisible = false
        capture.showSheet = true
    }

    fun showPreview(rawText: String, method: CaptureMethod, app: String, url: String = "", title: String = "") {
        val analysis = CaptureNormalizer.analyze(rawText)
        capture.method = method
        capture.sourceApp = app
        capture.sourceUrl = CaptureProvenanceExtractor.sanitizeUrl(url)
        capture.sourceTitle = title.trim().take(300)
        capture.manualFieldsVisible = false
        capture.preview = null
        capture.pendingAnalysis = null
        if (analysis.normalizedText.isBlank()) {
            message = "저장할 텍스트를 입력하세요."
            return
        }
        if (analysis.needsWordSelection) {
            capture.pendingAnalysis = analysis
            message = "문장이 들어왔습니다. 저장할 단어를 선택하세요."
            return
        }
        val result = DictionaryResolver.resolve(analysis, app, capture.sourceUrl, capture.sourceTitle)
        capture.preview = result
        capture.previewMeaning = result.meaningKo
        capture.previewExample = result.sourceSentence.ifBlank { result.exampleSentence }
        message = if (result.meaningKo.isBlank()) "뜻을 찾지 못했습니다. 직접 입력하거나 일단 저장할 수 있습니다." else "저장 전 preview를 확인하세요."
    }

    fun previewFromClipboard() {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
        showPreview(text, CaptureMethod.MANUAL, "clipboard")
    }

    fun selectCandidateWord(analysis: CaptureAnalysis, selected: String) {
        val selectedAnalysis = CaptureNormalizer.analyzeSelection(analysis.rawText, selected)
        val result = DictionaryResolver.resolve(selectedAnalysis, capture.sourceApp, capture.sourceUrl, capture.sourceTitle)
        capture.preview = result
        capture.previewMeaning = result.meaningKo
        capture.previewExample = selectedAnalysis.sourceSentence
        capture.pendingAnalysis = null
        capture.manualFieldsVisible = false
        message = "저장 전 preview를 확인하세요."
    }

    fun startDirectInputFromPreview() {
        val result = capture.preview ?: return
        capture.word = result.displayWord.ifBlank { result.lemma }
        capture.meaning = capture.previewMeaning
        capture.example = capture.previewExample
        capture.preview = null
        capture.pendingAnalysis = null
        capture.manualFieldsVisible = true
        message = ""
    }

    fun cancelPreview() {
        capture.preview = null
        capture.pendingAnalysis = null
        capture.manualFieldsVisible = false
        capture.showSheet = false
        message = "취소됨"
    }

    fun savePreview(result: DictionaryResult) {
        val now = Instant.now()
        val schedulerSettings = store.settings().toSchedulerSettings()
        val dueAt = DueTimeJitter.apply(Scheduler.initialDueAt(now, schedulerSettings), now, schedulerSettings)
        val normalizedLemma = result.lemma.ifBlank { CaptureNormalizer.lemma(result.displayWord) }
        val existingBefore = store.cards().firstOrNull { it.lemma.equals(normalizedLemma, ignoreCase = true) }
        val editedExample = capture.previewExample.trim()
        val defaultExample = result.sourceSentence.ifBlank { result.exampleSentence }.trim()
        val sourceSentence = when {
            existingBefore != null && result.sourceSentence.isBlank() && editedExample == defaultExample -> ""
            else -> editedExample.ifBlank { result.sourceSentence }
        }
        val method = capture.method
        val card = store.addResolvedCard(
            result = result.copy(
                meaningKo = capture.previewMeaning.trim(),
                exampleSentence = editedExample,
                sourceSentence = sourceSentence,
                sourceType = if (capture.previewMeaning.trim() != result.meaningKo.trim()) "user" else result.sourceType,
                sourceApp = capture.sourceApp,
                sourceUrl = capture.sourceUrl,
                sourceTitle = capture.sourceTitle
            ),
            captureMethod = method,
            dueAt = dueAt,
            now = now
        )
        message = if (card.meaningKo.isNotBlank()) {
            ReminderScheduler.schedule(context, card, now)
            if (existingBefore != null) {
                "${card.displayWord} 문맥 추가됨 · 카드 수 ${store.cards().size}개 유지"
            } else {
                "${card.displayWord} 저장됨 · 첫 알림까지 ${Duration.between(now, card.dueAt).toMinutes()}분"
            }
        } else {
            "${card.displayWord} 뜻 없이 저장됨 · 알림 보류"
        }
        capture.clearForm()
        capture.showSheet = false
        afterLocalChange()
        if (method != CaptureMethod.MANUAL) finishRequested = true
    }

    fun saveManual() {
        val trimmedWord = capture.word.trim()
        if (trimmedWord.isBlank()) {
            message = "단어를 입력하세요."
            return
        }
        if (capture.meaning.isBlank()) {
            showPreview(trimmedWord, CaptureMethod.MANUAL, "manual")
            return
        }
        val now = Instant.now()
        val schedulerSettings = store.settings().toSchedulerSettings()
        val dueAt = DueTimeJitter.apply(Scheduler.initialDueAt(now, schedulerSettings), now, schedulerSettings)
        val card = store.addCard(
            displayWord = trimmedWord,
            meaningKo = capture.meaning,
            exampleSentence = capture.example,
            dueAt = dueAt,
            now = now,
            captureMethod = CaptureMethod.MANUAL,
            definitionSourcePrimary = "user"
        )
        if (card.meaningKo.isNotBlank()) ReminderScheduler.schedule(context, card, now)
        capture.clearForm()
        capture.showSheet = false
        message = "${card.displayWord} 저장됨 · 첫 알림까지 ${Duration.between(now, card.dueAt).toMinutes()}분"
        afterLocalChange()
    }

    // ---- Card actions ----------------------------------------------------------------------

    fun deleteCard(card: VocabularyCard) {
        val deleted = store.softDeleteCard(card.id, Instant.now())
        if (deleted == null) {
            message = "${card.displayWord} 삭제 실패"
            return
        }
        ReminderScheduler.cancel(context, deleted)
        ReminderNotifications.cancel(context, deleted)
        message = "${card.displayWord} 삭제됨"
        afterLocalChange()
    }

    fun saveCardEdit(card: VocabularyCard, editedMeaning: String, editedExample: String) {
        val result = store.updateCardDetails(cardId = card.id, meaningKo = editedMeaning, exampleSentence = editedExample)
        if (result == null) {
            message = "${card.displayWord} 수정 실패"
            return
        }
        if (result.filledMissingMeaning) {
            ReminderScheduler.schedule(context, result.after)
            ReminderNotifications.clearStalePrompt(context, store)
            DailyReviewPlanner.planAndSchedule(context)
        }
        message = "${result.after.displayWord} 수정됨"
        afterLocalChange()
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
        message = "${result.after.displayWord} 뜻 후보를 현재 뜻으로 복원함"
        afterLocalChange()
    }

    fun reviewCard(card: VocabularyCard, response: ReviewResponse) {
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
        afterLocalChange()
    }

    fun handleRecommendation(card: VocabularyCard, action: String) {
        val now = Instant.now()
        message = when (action) {
            ReminderNotifications.RECOMMENDATION_START -> {
                store.markRecommendationStarted(card.id, now)
                store.findCard(card.id)?.let { ReminderScheduler.schedule(context, it, now) }
                context.getString(R.string.card_recommendation_started_message, card.displayWord)
            }
            ReminderNotifications.RECOMMENDATION_KNOWN -> {
                store.softDeleteRecommendation(card.id, "known", now)
                context.getString(R.string.card_recommendation_known_message, card.displayWord)
            }
            ReminderNotifications.RECOMMENDATION_DISMISS -> {
                store.softDeleteRecommendation(card.id, "dismissed", now)
                context.getString(R.string.card_recommendation_dismissed_message, card.displayWord)
            }
            else -> return
        }
        ReminderNotifications.clearActivePrompt(context, card.id)
        ReminderNotifications.cancel(context, card)
        DailyReviewPlanner.planAndSchedule(context, now)
        afterLocalChange()
    }

    // ---- Settings and planning -------------------------------------------------------------

    fun saveSettings() {
        store.saveSettings(settings)
        val summary = DailyReviewPlanner.planAndSchedule(context)
        QuietHoursBoundaryScheduler.schedule(context)
        val intro = if (summary.recommendationIntroShown) " · 추천 안내 표시" else ""
        message = "설정 저장됨 · 알림 ${summary.scheduledNotifications}개 편성$intro"
        afterLocalChange()
    }

    fun planToday() {
        val summary = DailyReviewPlanner.planAndSchedule(context)
        QuietHoursBoundaryScheduler.schedule(context)
        val intro = if (summary.recommendationIntroShown) ", 추천 안내 표시" else ""
        message = "오늘 편성: 추천 ${summary.generatedRecommendations}, 알림 ${summary.scheduledNotifications}, 재조정 ${summary.rebalancedCards}$intro"
        reload()
    }

    fun rebalance() {
        val count = DailyReviewPlanner.rebalanceDueCards(context)
        message = "복습 재조정 ${count}개"
        reload()
    }

    // ---- In-app study -----------------------------------------------------------------------

    var study by mutableStateOf<StudySession?>(null)
        private set

    fun todaySummary(now: Instant = Instant.now()): TodaySummary = StudyPlan.today(
        cards = library.cards,
        events = library.events,
        weakIds = library.weakWordIds,
        dailyGoal = settings.dailyWordGoal,
        timezone = settings.timezone,
        now = now
    )

    /** Due cards when there are any; otherwise a practice round that leaves the schedule alone. */
    fun startStudy() {
        val due = StudyPlan.dueQueue(library.cards, Instant.now())
        val practice = due.isEmpty()
        val queue = if (practice) StudyPlan.practiceQueue(library.cards, library.weakWordIds) else due
        if (queue.isEmpty()) return
        study = StudySession(cardIds = queue.map { it.id }, practice = practice)
    }

    fun revealStudyCard() {
        study = study?.copy(revealed = true)
    }

    fun answerStudyCard(response: ReviewResponse) {
        val session = study ?: return
        val card = session.currentId?.let { id -> library.cards.firstOrNull { it.id == id } }
        if (card != null && !session.practice) reviewCard(card, response)
        study = session.copy(
            index = session.index + 1,
            revealed = false,
            remembered = session.remembered + if (response == ReviewResponse.REMEMBERED) 1 else 0,
            forgotten = session.forgotten + if (response == ReviewResponse.FORGOTTEN) 1 else 0,
            later = session.later + if (response == ReviewResponse.UNAVAILABLE) 1 else 0
        )
    }

    fun closeStudy() {
        study = null
        message = ""
    }

    fun onRecommendedTabSelected() {
        DailyReviewPlanner.planAndSchedule(context)
        reload()
    }

    // ---- Sync ------------------------------------------------------------------------------

    fun runCloudSync() {
        val config = syncConfigOrNull() ?: return
        cloudSyncRunning = true
        message = "동기화 중..."
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { SyncCoordinator(store).sync(CloudSyncClient(config)) }
            }
            cloudSyncRunning = false
            result.fold(
                onSuccess = { syncResult ->
                    store.recordCloudSyncSuccess(syncResult.skippedSummary, syncResult.requestId)
                    message = if (syncResult.skippedSummary.isBlank()) {
                        "동기화 완료 · snapshot ${syncResult.snapshotJson.length}자"
                    } else {
                        "동기화 완료 · 스킵 ${syncResult.skippedSummary}"
                    }
                    ReminderNotifications.clearStalePrompt(context, store)
                    DailyReviewPlanner.planAndSchedule(context)
                },
                onFailure = { error ->
                    val errorMessage = error.message ?: error::class.java.simpleName
                    store.recordCloudSyncFailure(errorMessage, (error as? CloudSyncException)?.requestId)
                    message = "동기화 실패: $errorMessage"
                }
            )
            syncStatus = store.cloudSyncStatus()
            reload()
        }
    }

    fun testSyncConnection() {
        val config = syncConfigOrNull() ?: return
        syncConnectionTestRunning = true
        message = "연결 테스트 중..."
        viewModelScope.launch {
            val client = CloudSyncClient(config)
            val result = runCatching { withContext(Dispatchers.IO) { client.health() } }
            syncConnectionTestRunning = false
            result.fold(
                onSuccess = { health ->
                    store.recordCloudSyncSuccess(requestId = client.lastRequestId)
                    message = "동기화 연결 성공 · 카드 ${health.optInt("cards")}개"
                },
                onFailure = { error ->
                    val errorMessage = error.message ?: error::class.java.simpleName
                    store.recordCloudSyncFailure(errorMessage, (error as? CloudSyncException)?.requestId ?: client.lastRequestId)
                    message = "동기화 연결 실패: $errorMessage"
                }
            )
            syncStatus = store.cloudSyncStatus()
        }
    }

    fun refreshSyncStatus() {
        syncStatus = store.cloudSyncStatus()
    }

    private fun syncConfigOrNull(): CloudSyncConfig? {
        val serverUrl = syncServerUrl.trim()
        val token = syncBearerToken.trim()
        if (serverUrl.isBlank() || token.isBlank()) {
            message = syncMissingConfigurationMessage()
            return null
        }
        store.saveCloudSyncCredentials(CloudSyncCredentials(serverBaseUrl = serverUrl, bearerToken = token))
        return CloudSyncConfig(serverUrl, token)
    }

    fun exportLocalSync() {
        val payload = store.exportSyncBatchJson()
        val file = localSyncFile()
        message = runCatching {
            file.parentFile?.mkdirs()
            file.writeText(payload)
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("vocab-reminder-sync", payload))
        }.fold(
            onSuccess = { "Sync 내보내기 완료 · ${file.absolutePath}" },
            onFailure = { "Sync 내보내기 실패: ${it.message ?: it::class.java.simpleName}" }
        )
    }

    fun importLocalSync() {
        val file = localSyncFile()
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
        message = runCatching {
            store.importSyncBatchJson(raw)
            DailyReviewPlanner.planAndSchedule(context)
            QuietHoursBoundaryScheduler.schedule(context)
        }.fold(
            onSuccess = {
                reload()
                "Sync 가져오기 완료 · 카드 ${store.cards().size}개"
            },
            onFailure = { "Sync 가져오기 실패: ${it.message ?: it::class.java.simpleName}" }
        )
    }

    // ---- App update -----------------------------------------------------------------------

    fun checkForUpdate() {
        if (!AppUpdateInstaller.enabled || appUpdate.busy) return
        val config = updateConfigOrNull() ?: return
        appUpdate = AppUpdateState.Checking
        viewModelScope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { AppUpdateClient(config).fetchLatest() } }
            appUpdate = result.fold(
                onSuccess = { release ->
                    if (release != null && AppUpdatePolicy.isNewer(release, AppUpdateInstaller.installedVersionCode(context))) {
                        AppUpdateState.Available(release)
                    } else {
                        AppUpdateState.UpToDate
                    }
                },
                onFailure = { AppUpdateState.Failed(it.message ?: "업데이트 확인 실패") }
            )
        }
    }

    /** Downloads, verifies and hands the release to the system installer. */
    fun startUpdate(release: AppRelease) {
        if (!AppUpdateInstaller.enabled || appUpdate.busy) return
        val config = updateConfigOrNull() ?: return
        if (!AppUpdateInstaller.canRequestInstalls(context)) {
            appUpdate = AppUpdateState.NeedsPermission(release)
            showInstallPermissionDialog = true
            return
        }
        appUpdate = AppUpdateState.Downloading(release, 0)
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    AppUpdateInstaller.cleanupDownloads(context)
                    val apk = AppUpdateInstaller.downloadFile(context, release)
                    AppUpdateClient(config).download(release, apk) { written, total ->
                        val percent = (written * 100 / total).toInt()
                        if ((appUpdate as? AppUpdateState.Downloading)?.percent != percent) {
                            appUpdate = AppUpdateState.Downloading(release, percent)
                        }
                    }
                    AppUpdateInstaller.verify(context, apk, release)?.let { reason ->
                        apk.delete()
                        throw AppUpdateException(reason)
                    }
                    AppUpdateInstaller.install(context, apk)
                }
            }
            result.onFailure { AppUpdateInstaller.cleanupDownloads(context) }
            appUpdate = result.fold(
                onSuccess = { AppUpdateState.Installing(release) },
                onFailure = { AppUpdateState.Failed(it.message ?: it::class.java.simpleName, release) }
            )
        }
    }

    fun openInstallPermissionSettings() {
        showInstallPermissionDialog = false
        context.startActivity(AppUpdateInstaller.unknownSourcesSettingsIntent(context))
    }

    fun dismissInstallPermissionDialog() {
        showInstallPermissionDialog = false
    }

    /** Back from the "Install unknown apps" screen: continue if the switch is now on. */
    fun onResumeForUpdate() {
        val pending = appUpdate as? AppUpdateState.NeedsPermission ?: return
        if (AppUpdateInstaller.canRequestInstalls(context)) startUpdate(pending.release)
    }

    private fun onInstallOutcome(outcome: AppUpdateInstaller.InstallOutcome) {
        val release = when (val state = appUpdate) {
            is AppUpdateState.Installing -> state.release
            is AppUpdateState.Downloading -> state.release
            else -> null
        }
        appUpdate = when (outcome) {
            AppUpdateInstaller.InstallOutcome.WaitingForUser -> release?.let { AppUpdateState.Installing(it) } ?: appUpdate
            AppUpdateInstaller.InstallOutcome.Installed -> AppUpdateState.UpToDate
            is AppUpdateInstaller.InstallOutcome.Failed -> AppUpdateState.Failed(outcome.message, release)
        }
    }

    private fun updateConfigOrNull(): CloudSyncConfig? {
        val serverUrl = syncServerUrl.trim()
        val token = syncBearerToken.trim()
        if (serverUrl.isBlank() || token.isBlank()) {
            appUpdate = AppUpdateState.Failed("동기화 설정이 없어 업데이트를 확인할 수 없습니다.")
            return null
        }
        return CloudSyncConfig(serverUrl, token)
    }

    private fun localSyncFile(): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "vocab-reminder-sync.json")

    private fun afterLocalChange() {
        reload()
        SyncWorker.enqueueImmediate(context)
    }
}
