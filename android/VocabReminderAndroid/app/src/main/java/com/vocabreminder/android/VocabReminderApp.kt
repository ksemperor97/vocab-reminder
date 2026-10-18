package com.vocabreminder.android

import android.app.Activity
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.List
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vocabreminder.android.capture.CaptureMethod

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VocabReminderApp(initial: InitialCapture, model: AppViewModel = viewModel()) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val capture = model.capture
    val library = model.library
    val needsNotificationPermission = !model.notificationsAreAllowed
    val firstRunGuide = initial.text.isBlank() && initial.pairCode.isBlank() && model.onboarding.captureGuideCompletedAt == null

    var selectedTab by remember { mutableStateOf(AppTab.TODAY) }
    var showRecommended by remember { mutableStateOf(false) }
    var openCardId by remember { mutableStateOf<String?>(null) }
    var showSyncSettings by remember { mutableStateOf(false) }
    var showCaptureGuide by remember { mutableStateOf(firstRunGuide) }
    var showNotificationPermissionDialog by remember {
        mutableStateOf(initial.text.isBlank() && !firstRunGuide && needsNotificationPermission)
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        model.onNotificationPermissionResult(granted)
        showNotificationPermissionDialog = false
    }

    fun requestOrOpenNotificationSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotificationPermission(context)) {
            notificationPermissionLauncher.launch(POST_NOTIFICATIONS_PERMISSION)
        } else {
            showNotificationPermissionDialog = false
            openNotificationSettings(context)
        }
    }

    LaunchedEffect(Unit) { model.onStart(initial) }
    LaunchedEffect(model.finishRequested) {
        if (model.finishRequested) (context as? Activity)?.finish()
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                model.reload()
                model.refreshDeviceReadiness()
                model.onResumeForUpdate()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
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
                        model.completeCaptureGuide()
                        showCaptureGuide = false
                        showNotificationPermissionDialog = needsNotificationPermission
                    }
                ) { Text("안내 완료") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showCaptureGuide = false
                    showNotificationPermissionDialog = needsNotificationPermission
                }) { Text("나중에") }
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
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotificationPermission(context)) "권한 요청"
                        else "알림 설정 열기"
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showNotificationPermissionDialog = false }) { Text("나중에") }
            }
        )
    }

    if (model.showInstallPermissionDialog) {
        InstallPermissionDialog(
            onOpenSettings = model::openInstallPermissionSettings,
            onDismiss = model::dismissInstallPermissionDialog
        )
    }

    VocabTheme {
        val session = model.study
        if (session != null) {
            StudySessionScreen(
                session = session,
                cards = library.cards,
                contexts = library.contexts,
                definitionCandidates = library.definitionCandidates,
                weakIds = library.weakWordIds,
                onReveal = model::revealStudyCard,
                onAnswer = model::answerStudyCard,
                onClose = model::closeStudy
            )
            return@VocabTheme
        }
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = { AppUpdateBanner(state = model.appUpdate, onUpdate = model::startUpdate) },
            bottomBar = {
                AppNavigationBar(selectedTab = selectedTab, onTabSelected = { tab ->
                    selectedTab = tab
                    if (tab != AppTab.SETTINGS) showSyncSettings = false
                })
            },
            floatingActionButton = {
                if (selectedTab != AppTab.SETTINGS) {
                    FloatingActionButton(
                        modifier = Modifier.testTag("add_word_fab"),
                        onClick = { model.openManualCapture() },
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ) { Icon(Icons.Outlined.Add, contentDescription = "단어 추가") }
                }
            }
        ) { innerPadding ->
            Surface(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                when (selectedTab) {
                    AppTab.TODAY -> TodayScreen(
                        summary = model.todaySummary(),
                        timezone = model.settings.timezone,
                        message = model.message,
                        needsNotificationPermission = needsNotificationPermission,
                        onRequestNotificationPermission = { showNotificationPermissionDialog = true },
                        onStartStudy = model::startStudy,
                        serverConnected = model.serverConnected,
                        onConnect = { model.openConnect() }
                    )
                    AppTab.WORDBOOK -> WordbookScreen(
                        cards = library.cards,
                        weakWordIds = library.weakWordIds,
                        recommendationStatus = library.recommendationStatus,
                        message = model.message,
                        showRecommended = showRecommended,
                        onShowRecommended = { recommended ->
                            showRecommended = recommended
                            if (recommended) model.onRecommendedTabSelected()
                        },
                        onOpenCard = { openCardId = it.id }
                    )
                    AppTab.SETTINGS -> SettingsScreen(
                        settings = model.settings,
                        readinessItems = model.readinessItems,
                        showSyncSettings = showSyncSettings,
                        message = model.message,
                        onSettingsChange = { model.settings = it },
                        onSaveSettings = model::saveSettings,
                        onPlan = model::planToday,
                        onRebalance = model::rebalance,
                        onOpenNotificationSettings = { openNotificationSettings(context) },
                        onOpenBatterySettings = { openBatterySettings(context) },
                        onRefreshReadiness = model::refreshDeviceReadiness,
                        onRepairBackgroundWork = model::repairBackgroundWork,
                        onShowCaptureGuide = {
                            showNotificationPermissionDialog = false
                            showCaptureGuide = true
                        },
                        onOpenSync = {
                            model.refreshSyncStatus()
                            showSyncSettings = true
                        },
                        onBackFromSync = { showSyncSettings = false },
                        updateContent = {
                            AppUpdatePanel(
                                state = model.appUpdate,
                                installedVersion = model.installedVersionLabel,
                                onCheck = model::checkForUpdate,
                                onUpdate = model::startUpdate,
                                onOpenInstallPermission = model::openInstallPermissionSettings
                            )
                        },
                        connectionContent = {
                            ServerConnectionPanel(
                                connected = model.serverConnected,
                                paired = model.pairedServer,
                                serverUrl = model.syncServerUrl,
                                onConnect = { model.openConnect() },
                                onCreateCode = model::createCodeForOtherDevice,
                                onDisconnect = model::disconnectServer
                            )
                        },
                        syncContent = {
                            CloudSyncPanel(
                                serverUrl = model.syncServerUrl,
                                authToken = model.syncBearerToken,
                                status = model.syncStatus,
                                connectionTestRunning = model.syncConnectionTestRunning,
                                running = model.cloudSyncRunning,
                                onServerUrlChange = { model.syncServerUrl = it },
                                onAuthTokenChange = { model.syncBearerToken = it },
                                onTestConnection = model::testSyncConnection,
                                onSync = model::runCloudSync,
                                onLocalExport = model::exportLocalSync,
                                onLocalImport = model::importLocalSync
                            )
                        }
                    )
                }
            }
        }

        val openCard = openCardId?.let { id -> library.cards.firstOrNull { it.id == id && it.deletedAt == null } }
        if (openCardId != null && openCard == null) openCardId = null
        if (openCard != null) {
            ModalBottomSheet(onDismissRequest = { openCardId = null }) {
                Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 32.dp)) {
                    CardSummary(
                        card = openCard,
                        weak = openCard.id in library.weakWordIds,
                        recommendationReason = com.vocabreminder.android.recommendation.RecommendationEngine.reason(
                            openCard.lemma,
                            library.contexts.filter { it.captureMethod != CaptureMethod.RECOMMENDATION }.takeLast(50).map { it.sourceSentence }
                        ),
                        contexts = library.contexts.filter { it.cardId == openCard.id },
                        definitionCandidates = library.definitionCandidates.filter { it.cardId == openCard.id },
                        events = library.events.filter { it.cardId == openCard.id },
                        enrichmentState = library.enrichmentStates[openCard.id],
                        timezone = model.settings.timezone,
                        onEditSave = { meaning, example -> model.saveCardEdit(openCard, meaning, example) },
                        onRetryEnrichment = { model.retryDictionaryEnrichment(openCard) },
                        onSelectDefinitionCandidate = { candidateId -> model.selectDefinitionCandidate(openCard, candidateId) },
                        onReview = { response -> model.reviewCard(openCard, response) },
                        onRecommendationAction = { action -> model.handleRecommendation(openCard, action) },
                        onDelete = {
                            model.deleteCard(openCard)
                            openCardId = null
                        },
                        initiallyExpanded = true
                    )
                }
            }
        }

        if (model.showConnectSheet) {
            ModalBottomSheet(onDismissRequest = { model.showConnectSheet = false }) {
                ConnectSheetContent(
                    server = model.connectServer,
                    code = model.connectCode,
                    running = model.connectRunning,
                    error = model.connectError,
                    onServerChange = { model.connectServer = it },
                    onCodeChange = { model.connectCode = it },
                    onConnect = model::connect
                )
            }
        }
        model.codeForOtherDevice?.let { code ->
            PairingCodeDialog(code = code, serverUrl = model.syncServerUrl, onDismiss = model::dismissCodeForOtherDevice)
        }

        if (capture.showSheet) {
            ModalBottomSheet(onDismissRequest = { capture.showSheet = false }) {
                AddWordSheetContent(
                    word = capture.word,
                    meaning = capture.meaning,
                    example = capture.example,
                    message = model.message,
                    manualFieldsVisible = capture.manualFieldsVisible,
                    pendingAnalysis = capture.pendingAnalysis,
                    preview = capture.preview,
                    previewMeaning = capture.previewMeaning,
                    previewExample = capture.previewExample,
                    onWordChange = { capture.word = it },
                    onMeaningChange = { capture.meaning = it },
                    onExampleChange = { capture.example = it },
                    onPreview = { model.showPreview(capture.word, CaptureMethod.MANUAL, "manual") },
                    onShowManualFields = { capture.manualFieldsVisible = true },
                    onDirectInputFromPreview = model::startDirectInputFromPreview,
                    onClipboard = model::previewFromClipboard,
                    onSaveManual = model::saveManual,
                    onCandidateSelected = model::selectCandidateWord,
                    onPreviewMeaningChange = { capture.previewMeaning = it },
                    onPreviewExampleChange = { capture.previewExample = it },
                    onSavePreview = model::savePreview,
                    onCancelPreview = model::cancelPreview
                )
            }
        }
    }
}

@Composable
internal fun AppNavigationBar(selectedTab: AppTab, onTabSelected: (AppTab) -> Unit) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
        NavigationBarItem(
            modifier = Modifier.testTag("tab_today"),
            selected = selectedTab == AppTab.TODAY,
            onClick = { onTabSelected(AppTab.TODAY) },
            label = { Text("오늘") },
            icon = { Icon(Icons.Outlined.Home, contentDescription = null) }
        )
        NavigationBarItem(
            modifier = Modifier.testTag("tab_user_words"),
            selected = selectedTab == AppTab.WORDBOOK,
            onClick = { onTabSelected(AppTab.WORDBOOK) },
            label = { Text(stringResource(R.string.tab_user_words)) },
            icon = { Icon(Icons.Outlined.List, contentDescription = null) }
        )
        NavigationBarItem(
            modifier = Modifier.testTag("tab_settings"),
            selected = selectedTab == AppTab.SETTINGS,
            onClick = { onTabSelected(AppTab.SETTINGS) },
            label = { Text(stringResource(R.string.tab_settings)) },
            icon = { Icon(Icons.Outlined.Settings, contentDescription = null) }
        )
    }
}
