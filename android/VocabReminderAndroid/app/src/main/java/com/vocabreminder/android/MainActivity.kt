package com.vocabreminder.android

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import com.vocabreminder.android.capture.CaptureAnalysis
import com.vocabreminder.android.capture.CaptureMethod
import com.vocabreminder.android.capture.CaptureNormalizer
import com.vocabreminder.android.data.CloudSyncCredentials
import com.vocabreminder.android.data.LocalVocabularyStore
import com.vocabreminder.android.data.StoredReviewEvent
import com.vocabreminder.android.data.UserSettingsData
import com.vocabreminder.android.data.VocabularyCard
import com.vocabreminder.android.dictionary.DictionaryResolver
import com.vocabreminder.android.dictionary.DictionaryResult
import com.vocabreminder.android.notifications.DailyReviewPlanner
import com.vocabreminder.android.notifications.QuietHoursWorker
import com.vocabreminder.android.notifications.ReminderNotifications
import com.vocabreminder.android.notifications.ReminderScheduler
import com.vocabreminder.android.scheduler.Scheduler
import com.vocabreminder.android.sync.CloudSyncClient
import com.vocabreminder.android.sync.CloudSyncConfig
import com.vocabreminder.android.sync.FirebaseAuthRestClient
import com.vocabreminder.android.sync.FirebasePasswordAuthConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ReminderNotifications.ensureChannel(this)
        QuietHoursWorker.ensureScheduled(this)
        requestNotificationPermissionIfNeeded()

        val initialCapture = captureFromIntent(intent)

        setContent {
            VocabReminderApp(
                initialText = initialCapture.text,
                initialMethod = initialCapture.method,
                initialSourceApp = initialCapture.sourceApp
            )
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
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
    val events = remember { mutableStateListOf<StoredReviewEvent>() }
    val weakWordIds = remember { mutableStateOf(emptySet<String>()) }

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

    fun reload() {
        cards.clear()
        cards.addAll(store.cards().reversed())
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

    fun saveResolved(result: DictionaryResult, method: CaptureMethod) {
        val now = Instant.now()
        val dueAt = Scheduler.initialDueAt(now, settings.toSchedulerSettings())
        val finalResult = result.copy(
            meaningKo = previewMeaning.trim(),
            exampleSentence = previewExample.trim(),
            sourceSentence = previewExample.trim().ifBlank { result.sourceSentence },
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
            message = "${card.displayWord} 저장됨 · 첫 알림까지 ${Duration.between(now, card.dueAt).toMinutes()}분"
        } else {
            message = "${card.displayWord} 뜻 없이 저장됨 · 알림 보류"
        }
        word = ""
        meaning = ""
        example = ""
        preview = null
        pendingAnalysis = null
        reload()
    }

    fun saveManual() {
        val trimmedWord = word.trim()
        if (trimmedWord.isBlank()) {
            message = "단어를 입력하세요."
            return
        }
        if (meaning.isBlank()) {
            showPreview(trimmedWord, CaptureMethod.MANUAL, "manual")
            return
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

    LaunchedEffect(Unit) {
        reload()
        DailyReviewPlanner.planAndSchedule(context)
        if (initialText.isNotBlank()) {
            showPreview(initialText, initialMethod, initialSourceApp)
        }
    }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Text(
                        text = "Vocabulary Reminder",
                        style = MaterialTheme.typography.headlineSmall
                    )
                    Text(
                        text = "캡처한 단어를 preview로 확인하고 10분 뒤 알림으로 다시 묻습니다.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                item {
                    AddWordForm(
                        word = word,
                        meaning = meaning,
                        example = example,
                        message = message,
                        onWordChange = { word = it },
                        onMeaningChange = { meaning = it },
                        onExampleChange = { example = it },
                        onPreview = { showPreview(word, CaptureMethod.MANUAL, "manual") },
                        onClipboard = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                            showPreview(text, CaptureMethod.MANUAL, "clipboard")
                        },
                        onSave = { saveManual() }
                    )
                }

                pendingAnalysis?.let { analysis ->
                    item {
                        CandidateSelection(analysis) { selected ->
                            val selectedAnalysis = CaptureNormalizer.analyzeSelection(analysis.rawText, selected)
                            val result = DictionaryResolver.resolve(selectedAnalysis, sourceApp)
                            preview = result
                            previewMeaning = result.meaningKo
                            previewExample = selectedAnalysis.sourceSentence
                            pendingAnalysis = null
                            message = "저장 전 preview를 확인하세요."
                        }
                    }
                }

                preview?.let { result ->
                    item {
                        SavePreview(
                            result = result,
                            meaning = previewMeaning,
                            example = previewExample,
                            onMeaningChange = { previewMeaning = it },
                            onExampleChange = { previewExample = it },
                            onSave = { saveResolved(result, captureMethod) },
                            onCancel = {
                                preview = null
                                pendingAnalysis = null
                                message = "취소됨"
                            }
                        )
                    }
                }

                item {
                    SettingsPanel(
                        settings = settings,
                        onSettingsChange = { settings = it },
                        onSave = {
                            store.saveSettings(settings)
                            val summary = DailyReviewPlanner.planAndSchedule(context)
                            val intro = if (summary.recommendationIntroShown) " · 추천 안내 표시" else ""
                            message = "설정 저장됨 · 알림 ${summary.scheduledNotifications}개 편성$intro"
                            reload()
                        },
                        onPlan = {
                            val summary = DailyReviewPlanner.planAndSchedule(context)
                            val intro = if (summary.recommendationIntroShown) ", 추천 안내 표시" else ""
                            message = "오늘 편성: 추천 ${summary.generatedRecommendations}, 알림 ${summary.scheduledNotifications}, 재조정 ${summary.rebalancedCards}$intro"
                            reload()
                        },
                        onRebalance = {
                            val count = DailyReviewPlanner.rebalanceDueCards(context)
                            message = "복습 재조정 ${count}개"
                            reload()
                        }
                    )
                }

                item {
                    CloudSyncPanel(
                        firebaseWebApiKey = firebaseWebApiKey,
                        firebaseEmail = firebaseEmail,
                        firebasePassword = firebasePassword,
                        functionsUrl = cloudFunctionsUrl,
                        authToken = cloudAuthToken,
                        authRunning = firebaseAuthRunning,
                        running = cloudSyncRunning,
                        hasRefreshToken = firebaseRefreshToken.isNotBlank(),
                        onFirebaseWebApiKeyChange = { firebaseWebApiKey = it },
                        onFirebaseEmailChange = { firebaseEmail = it },
                        onFirebasePasswordChange = { firebasePassword = it },
                        onFunctionsUrlChange = { cloudFunctionsUrl = it },
                        onAuthTokenChange = { cloudAuthToken = it },
                        onSignIn = { signInFirebase() },
                        onRefreshToken = { refreshFirebaseToken() },
                        onSync = { runCloudSync() }
                    )
                }

                item {
                    Text(
                        text = "저장된 단어 ${cards.size}개 · 취약 단어 ${weakWordIds.value.size}개",
                        style = MaterialTheme.typography.titleMedium
                    )
                }

                if (cards.isEmpty()) {
                    item {
                        Text(
                            text = "아직 저장된 단어가 없습니다.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                } else {
                    items(cards, key = { it.id }) { card ->
                        CardSummary(card, weak = card.id in weakWordIds.value)
                    }
                }

                item {
                    Text(
                        text = "복습 이벤트 ${events.size}개",
                        style = MaterialTheme.typography.titleMedium
                    )
                }

                items(events.take(8), key = { it.id }) { event ->
                    Text(
                        text = "${event.response.name.lowercase()} · ${event.previousStatus.name.lowercase()} → ${event.nextStatus.name.lowercase()}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
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
    onFirebaseWebApiKeyChange: (String) -> Unit,
    onFirebaseEmailChange: (String) -> Unit,
    onFirebasePasswordChange: (String) -> Unit,
    onFunctionsUrlChange: (String) -> Unit,
    onAuthTokenChange: (String) -> Unit,
    onSignIn: () -> Unit,
    onRefreshToken: () -> Unit,
    onSync: () -> Unit
) {
    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Cloud sync", style = MaterialTheme.typography.titleMedium)
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
                modifier = Modifier.testTag("firebase_sign_in_button"),
                enabled = !authRunning,
                onClick = onSignIn
            ) {
                Text(if (authRunning) "로그인 중" else "Firebase 로그인")
            }
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
            Button(
                modifier = Modifier.testTag("cloud_sync_button"),
                enabled = !running,
                onClick = onSync
            ) {
                Text(if (running) "동기화 중" else "Cloud sync")
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
            Text("설정", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField("하루 목표", settings.dailyWordGoal.toString()) {
                    onSettingsChange(settings.copy(dailyWordGoal = it.toIntOrNull()?.coerceAtLeast(1) ?: settings.dailyWordGoal))
                }
                NumberField("큐 상한", settings.maxQueueSize.toString()) {
                    onSettingsChange(settings.copy(maxQueueSize = it.toIntOrNull()?.coerceAtLeast(1) ?: settings.maxQueueSize))
                }
                NumberField("미루기", settings.snoozeDurationMinutes.toString()) {
                    onSettingsChange(settings.copy(snoozeDurationMinutes = it.toLongOrNull()?.coerceAtLeast(1) ?: settings.snoozeDurationMinutes))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TimeField("잠자는 시작", settings.quietHoursStart.toString()) {
                    runCatching { LocalTime.parse(it) }.getOrNull()?.let { time -> onSettingsChange(settings.copy(quietHoursStart = time)) }
                }
                TimeField("잠자는 끝", settings.quietHoursEnd.toString()) {
                    runCatching { LocalTime.parse(it) }.getOrNull()?.let { time -> onSettingsChange(settings.copy(quietHoursEnd = time)) }
                }
                TimeField("시간대", settings.timezone.id) {
                    runCatching { ZoneId.of(it) }.getOrNull()?.let { zone -> onSettingsChange(settings.copy(timezone = zone)) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("추천 단어")
                Switch(
                    checked = settings.enableWordRecommendations,
                    onCheckedChange = { onSettingsChange(settings.copy(enableWordRecommendations = it)) }
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onSave) { Text("설정 저장") }
                Button(onClick = onPlan) { Text("오늘 편성") }
                Button(onClick = onRebalance) { Text("복습 재조정") }
            }
        }
    }
}

@Composable
private fun NumberField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        modifier = Modifier.fillMaxWidth(0.3f),
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true
    )
}

@Composable
private fun TimeField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        modifier = Modifier.fillMaxWidth(0.3f),
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true
    )
}

@Composable
private fun CardSummary(card: VocabularyCard, weak: Boolean) {
    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            Text(text = card.displayWord, style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = card.meaningKo.ifBlank { "뜻 없음 · 알림 보류" }, style = MaterialTheme.typography.bodyMedium)
            if (card.exampleSentence.isNotBlank()) {
                Text(text = card.exampleSentence, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(modifier = Modifier.height(6.dp))
            val weakLabel = if (weak) " · weak" else ""
            Text(
                text = "wordbook=${card.wordbook.name.lowercase()} · status=${card.status.name.lowercase()} · interval=${card.intervalDays}d · due=${card.dueAt}$weakLabel",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
