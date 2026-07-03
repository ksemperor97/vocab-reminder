package com.vocabreminder.android

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import com.vocabreminder.android.capture.CaptureAnalysis
import com.vocabreminder.android.capture.CaptureMethod
import com.vocabreminder.android.capture.CaptureNormalizer
import com.vocabreminder.android.data.CloudSyncCredentials
import com.vocabreminder.android.data.LocalVocabularyStore
import com.vocabreminder.android.data.StoredContext
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
import com.vocabreminder.android.scheduler.ReviewResponse
import com.vocabreminder.android.scheduler.Scheduler
import com.vocabreminder.android.scheduler.Wordbook
import com.vocabreminder.android.sync.CloudSyncClient
import com.vocabreminder.android.sync.CloudSyncConfig
import com.vocabreminder.android.sync.FirebaseAuthRestClient
import com.vocabreminder.android.sync.FirebasePasswordAuthConfig
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
                initialSourceApp = initialCapture.sourceApp
            )
        }
    }

    private fun captureFromIntent(intent: Intent?): InitialCapture {
        return when (intent?.action) {
            Intent.ACTION_PROCESS_TEXT -> InitialCapture(
                text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString().orEmpty(),
                method = CaptureMethod.ANDROID_PROCESS_TEXT,
                sourceApp = "Android selection"
            )
            Intent.ACTION_SEND -> InitialCapture(
                text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty(),
                method = CaptureMethod.ANDROID_SHARE,
                sourceApp = "Android share"
            )
            else -> InitialCapture()
        }
    }
}

private data class InitialCapture(
    val text: String = "",
    val method: CaptureMethod = CaptureMethod.MANUAL,
    val sourceApp: String = "manual"
)

private enum class AppTab {
    USER,
    RECOMMENDED,
    SETTINGS
}

private fun hasNotificationPermission(context: Context): Boolean {
    return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
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
    initialSourceApp: String
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { LocalVocabularyStore(context) }
    val initialCloudCredentials = remember { store.cloudSyncCredentials() }
    val cards = remember { mutableStateListOf<VocabularyCard>() }
    val contexts = remember { mutableStateListOf<StoredContext>() }
    val events = remember { mutableStateListOf<StoredReviewEvent>() }
    val weakWordIds = remember { mutableStateOf(emptySet<String>()) }

    var selectedTab by remember { mutableStateOf(AppTab.USER) }
    var showAddSheet by remember { mutableStateOf(initialText.isNotBlank()) }
    var showSyncSettings by remember { mutableStateOf(false) }
    var word by remember { mutableStateOf("") }
    var meaning by remember { mutableStateOf("") }
    var example by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var pendingAnalysis by remember { mutableStateOf<CaptureAnalysis?>(null) }
    var preview by remember { mutableStateOf<DictionaryResult?>(null) }
    var previewMeaning by remember { mutableStateOf("") }
    var previewExample by remember { mutableStateOf("") }
    var captureMethod by remember { mutableStateOf(initialMethod) }
    var sourceApp by remember { mutableStateOf(initialSourceApp) }
    var settings by remember { mutableStateOf(store.settings()) }
    var firebaseWebApiKey by remember { mutableStateOf(initialCloudCredentials.firebaseWebApiKey) }
    var firebaseEmail by remember { mutableStateOf("") }
    var firebasePassword by remember { mutableStateOf("") }
    var firebaseRefreshToken by remember { mutableStateOf(initialCloudCredentials.refreshToken) }
    var cloudFunctionsUrl by remember { mutableStateOf(initialCloudCredentials.functionsBaseUrl) }
    var cloudAuthToken by remember { mutableStateOf("") }
    var firebaseAuthRunning by remember { mutableStateOf(false) }
    var cloudSyncRunning by remember { mutableStateOf(false) }
    var notificationPermissionGranted by remember { mutableStateOf(hasNotificationPermission(context)) }
    var showNotificationPermissionDialog by remember { mutableStateOf(!notificationPermissionGranted) }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notificationPermissionGranted = granted
        showNotificationPermissionDialog = false
    }
    val needsNotificationPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notificationPermissionGranted

    fun reload() {
        cards.clear()
        cards.addAll(store.cards().reversed())
        contexts.clear()
        contexts.addAll(store.contexts().reversed())
        events.clear()
        events.addAll(store.events().reversed())
        weakWordIds.value = store.weakWordIds()
        settings = store.settings()
    }

    fun showPreview(rawText: String, method: CaptureMethod, app: String) {
        val analysis = CaptureNormalizer.analyze(rawText)
        captureMethod = method
        sourceApp = app
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
        val result = DictionaryResolver.resolve(analysis, app)
        preview = result
        previewMeaning = result.meaningKo
        previewExample = result.sourceSentence.ifBlank { result.exampleSentence }
        message = if (result.meaningKo.isBlank()) "뜻을 찾지 못했습니다. 직접 입력하거나 일단 저장할 수 있습니다." else "저장 전 preview를 확인하세요."
    }

    fun saveResolved(result: DictionaryResult, method: CaptureMethod): Boolean {
        val now = Instant.now()
        val dueAt = Scheduler.initialDueAt(now, settings.toSchedulerSettings())
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
            sourceApp = sourceApp
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
        preview = null
        pendingAnalysis = null
        reload()
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
        val dueAt = Scheduler.initialDueAt(now, settings.toSchedulerSettings())
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
        message = "${card.displayWord} 저장됨 · 첫 알림까지 ${Duration.between(now, card.dueAt).toMinutes()}분"
        reload()
        return true
    }

    fun runCloudSync() {
        val functionsUrl = cloudFunctionsUrl.trim()
        val token = cloudAuthToken.trim()
        if (functionsUrl.isBlank() || token.isBlank()) {
            message = "Functions URL과 Firebase Auth ID token을 입력하세요."
            return
        }
        if (firebaseRefreshToken.isNotBlank() || firebaseWebApiKey.isNotBlank() || functionsUrl.isNotBlank()) {
            store.saveCloudSyncCredentials(
                CloudSyncCredentials(
                    firebaseWebApiKey = firebaseWebApiKey.trim(),
                    functionsBaseUrl = functionsUrl,
                    refreshToken = firebaseRefreshToken
                )
            )
        }
        cloudSyncRunning = true
        message = "동기화 중..."
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    store.syncWithCloud(CloudSyncClient(CloudSyncConfig(functionsUrl, token)))
                }
            }
            cloudSyncRunning = false
            result.fold(
                onSuccess = { snapshot ->
                    message = "Cloud sync 완료 · snapshot ${snapshot.length}자"
                    reload()
                    DailyReviewPlanner.planAndSchedule(context)
                },
                onFailure = { error ->
                    message = "Cloud sync 실패: ${error.message ?: error::class.java.simpleName}"
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
        message = "${card.displayWord} 삭제됨"
    }

    fun signInFirebase() {
        val apiKey = firebaseWebApiKey.trim()
        val emailValue = firebaseEmail.trim()
        val passwordValue = firebasePassword
        if (apiKey.isBlank() || emailValue.isBlank() || passwordValue.isBlank()) {
            message = "Firebase Web API key, email, password를 입력하세요."
            return
        }
        firebaseAuthRunning = true
        message = "Firebase 로그인 중..."
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    FirebaseAuthRestClient().signInWithPassword(
                        FirebasePasswordAuthConfig(
                            webApiKey = apiKey,
                            email = emailValue,
                            password = passwordValue
                        )
                    )
                }
            }
            firebaseAuthRunning = false
            result.fold(
                onSuccess = { session ->
                    cloudAuthToken = session.idToken
                    firebaseRefreshToken = session.refreshToken
                    store.saveCloudSyncCredentials(
                        CloudSyncCredentials(
                            firebaseWebApiKey = apiKey,
                            functionsBaseUrl = cloudFunctionsUrl.trim(),
                            refreshToken = session.refreshToken
                        )
                    )
                    firebasePassword = ""
                    message = "Firebase 로그인 완료 · ID token ${session.expiresInSeconds}초 유효"
                },
                onFailure = { error ->
                    message = "Firebase 로그인 실패: ${error.message ?: error::class.java.simpleName}"
                }
            )
        }
    }

    fun refreshFirebaseToken() {
        val apiKey = firebaseWebApiKey.trim()
        val refreshToken = firebaseRefreshToken.trim()
        if (apiKey.isBlank() || refreshToken.isBlank()) {
            message = "저장된 Firebase Web API key와 refresh token이 필요합니다."
            return
        }
        firebaseAuthRunning = true
        message = "ID token 갱신 중..."
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    FirebaseAuthRestClient().refreshIdToken(apiKey, refreshToken)
                }
            }
            firebaseAuthRunning = false
            result.fold(
                onSuccess = { session ->
                    cloudAuthToken = session.idToken
                    firebaseRefreshToken = session.refreshToken
                    store.saveCloudSyncCredentials(
                        CloudSyncCredentials(
                            firebaseWebApiKey = apiKey,
                            functionsBaseUrl = cloudFunctionsUrl.trim(),
                            refreshToken = session.refreshToken
                        )
                    )
                    message = "ID token 갱신 완료 · ${session.expiresInSeconds}초 유효"
                },
                onFailure = { error ->
                    message = "ID token 갱신 실패: ${error.message ?: error::class.java.simpleName}"
                }
            )
        }
    }

    fun logoutFirebase() {
        firebaseRefreshToken = ""
        cloudAuthToken = ""
        store.saveCloudSyncCredentials(
            CloudSyncCredentials(
                firebaseWebApiKey = firebaseWebApiKey.trim(),
                functionsBaseUrl = cloudFunctionsUrl.trim(),
                refreshToken = ""
            )
        )
        message = "로그아웃됨"
    }

    LaunchedEffect(Unit) {
        reload()
        DailyReviewPlanner.planAndSchedule(context)
        if (initialText.isNotBlank()) {
            showPreview(initialText, initialMethod, initialSourceApp)
        }
    }

    if (showNotificationPermissionDialog && needsNotificationPermission) {
        AlertDialog(
            onDismissRequest = { showNotificationPermissionDialog = false },
            title = { Text("복습 알림 권한이 필요합니다") },
            text = {
                Text("Vocabulary Reminder는 저장한 단어를 알림으로 다시 묻는 앱입니다. 알림을 허용해야 10분 뒤 복습, 못외웠다 재확인, 나중에 하기 흐름을 받을 수 있습니다.")
            },
            confirmButton = {
                TextButton(onClick = { notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }) {
                    Text("권한 요청")
                }
            },
            dismissButton = {
                TextButton(onClick = { showNotificationPermissionDialog = false }) {
                    Text("나중에")
                }
            }
        )
    }

    MaterialTheme {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            bottomBar = {
                AppNavigationBar(
                    selectedTab = selectedTab,
                    onTabSelected = { tab ->
                        selectedTab = tab
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
                        events = events,
                        weakWordIds = weakWordIds.value,
                        timezone = settings.timezone,
                        needsNotificationPermission = needsNotificationPermission,
                        onRequestNotificationPermission = { showNotificationPermissionDialog = true },
                        onDelete = { deleteCard(it) }
                    )
                    AppTab.RECOMMENDED -> RecommendationWordbookScreen(
                        cards = cards.filter { it.wordbook == Wordbook.RECOMMENDED },
                        contexts = contexts,
                        events = events,
                        weakWordIds = weakWordIds.value,
                        timezone = settings.timezone,
                        onDelete = { deleteCard(it) }
                    )
                    AppTab.SETTINGS -> SettingsScreen(
                        settings = settings,
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
                        onOpenSync = { showSyncSettings = true },
                        onBackFromSync = { showSyncSettings = false },
                        syncContent = {
                            CloudSyncPanel(
                                firebaseWebApiKey = firebaseWebApiKey,
                                firebaseEmail = firebaseEmail,
                                firebasePassword = firebasePassword,
                                functionsUrl = cloudFunctionsUrl,
                                authToken = cloudAuthToken,
                                authRunning = firebaseAuthRunning,
                                running = cloudSyncRunning,
                                hasRefreshToken = firebaseRefreshToken.isNotBlank(),
                                debugFields = BuildConfig.DEBUG,
                                onFirebaseWebApiKeyChange = { firebaseWebApiKey = it },
                                onFirebaseEmailChange = { firebaseEmail = it },
                                onFirebasePasswordChange = { firebasePassword = it },
                                onFunctionsUrlChange = { cloudFunctionsUrl = it },
                                onAuthTokenChange = { cloudAuthToken = it },
                                onSignIn = { signInFirebase() },
                                onSignOut = { logoutFirebase() },
                                onRefreshToken = { refreshFirebaseToken() },
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
                    pendingAnalysis = pendingAnalysis,
                    preview = preview,
                    previewMeaning = previewMeaning,
                    previewExample = previewExample,
                    onWordChange = { word = it },
                    onMeaningChange = { meaning = it },
                    onExampleChange = { example = it },
                    onPreview = { showPreview(word, CaptureMethod.MANUAL, "manual") },
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
                        val result = DictionaryResolver.resolve(selectedAnalysis, sourceApp)
                        preview = result
                        previewMeaning = result.meaningKo
                        previewExample = selectedAnalysis.sourceSentence
                        pendingAnalysis = null
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
    events: List<StoredReviewEvent>,
    weakWordIds: Set<String>,
    timezone: ZoneId,
    needsNotificationPermission: Boolean,
    onRequestNotificationPermission: () -> Unit,
    onDelete: (VocabularyCard) -> Unit
) {
    WordbookScreen(
        title = stringResource(R.string.tab_user_words),
        cards = cards,
        contexts = contexts,
        events = events,
        weakWordIds = weakWordIds,
        timezone = timezone,
        emptyText = "아직 저장된 단어가 없습니다.",
        header = {
            val now = Instant.now()
            val dueToday = cards.count { card ->
                val days = ChronoUnit.DAYS.between(
                    now.atZone(timezone).toLocalDate(),
                    card.dueAt.atZone(timezone).toLocalDate()
                )
                days <= 0
            }
            Text(
                text = "오늘 due ${dueToday} · 취약 ${weakWordIds.size} · 전체 ${cards.size}",
                style = MaterialTheme.typography.titleMedium
            )
            if (needsNotificationPermission) {
                NotificationPermissionCard(onRequestNotificationPermission)
            }
        },
        onDelete = onDelete
    )
}

@Composable
private fun RecommendationWordbookScreen(
    cards: List<VocabularyCard>,
    contexts: List<StoredContext>,
    events: List<StoredReviewEvent>,
    weakWordIds: Set<String>,
    timezone: ZoneId,
    onDelete: (VocabularyCard) -> Unit
) {
    WordbookScreen(
        title = stringResource(R.string.tab_recommendations),
        cards = cards,
        contexts = contexts,
        events = events,
        weakWordIds = weakWordIds,
        timezone = timezone,
        emptyText = "추천 단어가 없습니다.",
        header = {
            Text(
                text = "추천 단어 ${cards.size}개",
                style = MaterialTheme.typography.titleMedium
            )
        },
        onDelete = onDelete
    )
}

@Composable
private fun WordbookScreen(
    title: String,
    cards: List<VocabularyCard>,
    contexts: List<StoredContext>,
    events: List<StoredReviewEvent>,
    weakWordIds: Set<String>,
    timezone: ZoneId,
    emptyText: String,
    header: @Composable () -> Unit,
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
        if (cards.isEmpty()) {
            item {
                Text(emptyText, style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            items(cards, key = { it.id }) { card ->
                CardSummary(
                    card = card,
                    weak = card.id in weakWordIds,
                    contexts = contexts.filter { it.cardId == card.id },
                    events = events.filter { it.cardId == card.id },
                    timezone = timezone,
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
                Text("알림 권한 요청")
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    settings: UserSettingsData,
    showSyncSettings: Boolean,
    message: String,
    onSettingsChange: (UserSettingsData) -> Unit,
    onSaveSettings: () -> Unit,
    onPlan: () -> Unit,
    onRebalance: () -> Unit,
    onOpenSync: () -> Unit,
    onBackFromSync: () -> Unit,
    syncContent: @Composable () -> Unit
) {
    LazyColumn(
        modifier = Modifier
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
                    Text(message, style = MaterialTheme.typography.bodySmall)
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
    pendingAnalysis: CaptureAnalysis?,
    preview: DictionaryResult?,
    previewMeaning: String,
    previewExample: String,
    onWordChange: (String) -> Unit,
    onMeaningChange: (String) -> Unit,
    onExampleChange: (String) -> Unit,
    onPreview: () -> Unit,
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
                message = message,
                onWordChange = onWordChange,
                onMeaningChange = onMeaningChange,
                onExampleChange = onExampleChange,
                onPreview = onPreview,
                onClipboard = onClipboard,
                onSave = onSaveManual
            )
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
    firebaseWebApiKey: String,
    firebaseEmail: String,
    firebasePassword: String,
    functionsUrl: String,
    authToken: String,
    authRunning: Boolean,
    running: Boolean,
    hasRefreshToken: Boolean,
    debugFields: Boolean,
    onFirebaseWebApiKeyChange: (String) -> Unit,
    onFirebaseEmailChange: (String) -> Unit,
    onFirebasePasswordChange: (String) -> Unit,
    onFunctionsUrlChange: (String) -> Unit,
    onAuthTokenChange: (String) -> Unit,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    onRefreshToken: () -> Unit,
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
                text = if (hasRefreshToken) "로그인 상태: refresh token 저장됨" else "로그인 상태: 로그인 필요",
                style = MaterialTheme.typography.bodyMedium
            )
            Text("마지막 동기화: 아직 없음", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    modifier = Modifier.testTag("firebase_sign_in_button"),
                    enabled = !authRunning,
                    onClick = {
                        if (hasRefreshToken) {
                            onSignOut()
                        } else {
                            onSignIn()
                        }
                    }
                ) {
                    Text(
                        when {
                            authRunning -> "로그인 중"
                            hasRefreshToken -> "로그아웃"
                            else -> "로그인"
                        }
                    )
                }
                Button(
                    modifier = Modifier.testTag("cloud_sync_button"),
                    enabled = !running,
                    onClick = onSync
                ) {
                    Text(if (running) "동기화 중" else "지금 동기화")
                }
            }
            if (debugFields) {
                Text("Debug sync fields", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        modifier = Modifier.testTag("local_sync_export_button"),
                        onClick = onLocalExport
                    ) {
                        Text("Sync 내보내기")
                    }
                    Button(
                        modifier = Modifier.testTag("local_sync_import_button"),
                        onClick = onLocalImport
                    ) {
                        Text("Sync 가져오기")
                    }
                }
                OutlinedTextField(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("firebase_web_api_key_field"),
                    value = firebaseWebApiKey,
                    onValueChange = onFirebaseWebApiKeyChange,
                    label = { Text("Firebase Web API key") },
                    singleLine = true
                )
                OutlinedTextField(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("firebase_email_field"),
                    value = firebaseEmail,
                    onValueChange = onFirebaseEmailChange,
                    label = { Text("Firebase email") },
                    singleLine = true
                )
                OutlinedTextField(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("firebase_password_field"),
                    value = firebasePassword,
                    onValueChange = onFirebasePasswordChange,
                    label = { Text("Firebase password") },
                    singleLine = true
                )
                Button(
                    modifier = Modifier.testTag("firebase_refresh_button"),
                    enabled = !authRunning && hasRefreshToken,
                    onClick = onRefreshToken
                ) {
                    Text(if (authRunning) "갱신 중" else "ID token 갱신")
                }
                OutlinedTextField(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("sync_functions_url_field"),
                    value = functionsUrl,
                    onValueChange = onFunctionsUrlChange,
                    label = { Text("Functions URL") },
                    singleLine = true
                )
                OutlinedTextField(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("sync_auth_token_field"),
                    value = authToken,
                    onValueChange = onAuthTokenChange,
                    label = { Text("Firebase Auth ID token") },
                    singleLine = true
                )
            }
        }
    }
}

@Composable
private fun AddWordForm(
    word: String,
    meaning: String,
    example: String,
    message: String,
    onWordChange: (String) -> Unit,
    onMeaningChange: (String) -> Unit,
    onExampleChange: (String) -> Unit,
    onPreview: () -> Unit,
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
                    modifier = Modifier.testTag("save_button"),
                    onClick = onSave
                ) {
                    Text("저장")
                }
            }
            if (message.isNotBlank()) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
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
            Text(analysis.normalizedText, style = MaterialTheme.typography.bodySmall)
            analysis.candidates.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { candidate ->
                        Button(
                            modifier = Modifier.testTag("candidate_$candidate"),
                            onClick = { onSelect(candidate) }
                        ) {
                            Text(candidate)
                        }
                    }
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
            Text("${result.displayWord} → ${result.lemma}", style = MaterialTheme.typography.titleMedium)
            if (result.partOfSpeech.isNotBlank()) {
                Text("${result.partOfSpeech} · ${result.definitionEn}", style = MaterialTheme.typography.bodySmall)
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
            Text("출처: ${result.sourceApp.ifBlank { "manual" }} · ${result.sourceType}", style = MaterialTheme.typography.bodySmall)
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
private fun SettingsPanel(
    settings: UserSettingsData,
    onSettingsChange: (UserSettingsData) -> Unit,
    onSave: () -> Unit,
    onPlan: () -> Unit,
    onRebalance: () -> Unit
) {
    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.titleMedium)
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                NumberField(stringResource(R.string.settings_daily_goal_label), settings.dailyWordGoal.toString()) {
                    onSettingsChange(settings.copy(dailyWordGoal = it.toIntOrNull()?.coerceAtLeast(1) ?: settings.dailyWordGoal))
                }
                NumberField(stringResource(R.string.settings_queue_cap_label), settings.maxQueueSize.toString()) {
                    onSettingsChange(settings.copy(maxQueueSize = it.toIntOrNull()?.coerceAtLeast(1) ?: settings.maxQueueSize))
                }
                NumberField(stringResource(R.string.settings_snooze_label), settings.snoozeDurationMinutes.toString()) {
                    onSettingsChange(settings.copy(snoozeDurationMinutes = it.toLongOrNull()?.coerceAtLeast(1) ?: settings.snoozeDurationMinutes))
                }
                TimeField(stringResource(R.string.settings_quiet_start_label), settings.quietHoursStart.toString()) {
                    runCatching { LocalTime.parse(it) }.getOrNull()?.let { time -> onSettingsChange(settings.copy(quietHoursStart = time)) }
                }
                TimeField(stringResource(R.string.settings_quiet_end_label), settings.quietHoursEnd.toString()) {
                    runCatching { LocalTime.parse(it) }.getOrNull()?.let { time -> onSettingsChange(settings.copy(quietHoursEnd = time)) }
                }
                TimeField(stringResource(R.string.settings_timezone_label), settings.timezone.id) {
                    runCatching { ZoneId.of(it) }.getOrNull()?.let { zone -> onSettingsChange(settings.copy(timezone = zone)) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.settings_recommendations_label))
                Switch(
                    checked = settings.enableWordRecommendations,
                    onCheckedChange = { onSettingsChange(settings.copy(enableWordRecommendations = it)) }
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
    SettingsTextField(label = label, value = value, onChange = onChange)
}

@Composable
private fun TimeField(label: String, value: String, onChange: (String) -> Unit) {
    SettingsTextField(label = label, value = value, onChange = onChange)
}

@Composable
private fun SettingsTextField(label: String, value: String, onChange: (String) -> Unit) {
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
            singleLine = true
        )
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

@Composable
private fun CardSummary(
    card: VocabularyCard,
    weak: Boolean,
    contexts: List<StoredContext>,
    events: List<StoredReviewEvent>,
    timezone: ZoneId,
    onDelete: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
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
            Text(
                text = card.meaningKo.ifBlank { "뜻 없음 · 알림 보류" },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusBadge(card)
                if (weak) {
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
            Text(
                text = "다음 복습: ${relativeDueLabel(card.dueAt, timezone)}",
                style = MaterialTheme.typography.bodySmall
            )
            if (expanded) {
                if (card.exampleSentence.isNotBlank()) {
                    Text(text = "예문: ${card.exampleSentence}", style = MaterialTheme.typography.bodySmall)
                }
                contexts.take(3).forEach { context ->
                    if (context.sourceSentence.isNotBlank()) {
                        Text(text = "문맥: ${context.sourceSentence}", style = MaterialTheme.typography.bodySmall)
                    }
                }
                events.take(3).forEach { event ->
                    Text(
                        text = "기록: ${reviewResponseLabel(event.response)} · ${statusLabel(event.previousStatus)} → ${statusLabel(event.nextStatus)}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (BuildConfig.DEBUG) {
                    val weakLabel = if (weak) " · weak" else ""
                    Text(
                        text = "wordbook=${card.wordbook.name.lowercase()} · status=${card.status.name.lowercase()} · interval=${card.intervalDays}d · due=${card.dueAt}$weakLabel",
                        style = MaterialTheme.typography.bodySmall
                    )
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
private fun StatusBadge(card: VocabularyCard) {
    val missingMeaning = card.meaningKo.isBlank()
    val containerColor = when {
        missingMeaning -> MaterialTheme.colorScheme.surface
        card.status == CardStatus.NEW -> MaterialTheme.colorScheme.primaryContainer
        card.status == CardStatus.LEARNING -> MaterialTheme.colorScheme.secondaryContainer
        card.status == CardStatus.REVIEW -> MaterialTheme.colorScheme.tertiaryContainer
        card.status == CardStatus.RELEARNING -> MaterialTheme.colorScheme.errorContainer
        card.status == CardStatus.MASTERED -> MaterialTheme.colorScheme.surfaceVariant
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    Surface(
        color = containerColor,
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
