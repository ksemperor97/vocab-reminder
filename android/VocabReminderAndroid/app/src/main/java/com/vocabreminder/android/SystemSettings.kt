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


internal enum class AppTab {
    USER,
    RECOMMENDED,
    SETTINGS
}

internal const val POST_NOTIFICATIONS_PERMISSION = "android.permission.POST_NOTIFICATIONS"

internal fun hasNotificationPermission(context: Context): Boolean {
    return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ActivityCompat.checkSelfPermission(context, POST_NOTIFICATIONS_PERMISSION) == PackageManager.PERMISSION_GRANTED
}

internal fun notificationsAllowed(context: Context): Boolean {
    return hasNotificationPermission(context) && NotificationManagerCompat.from(context).areNotificationsEnabled()
}

internal fun isBatteryOptimizationExempt(context: Context): Boolean {
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
    return powerManager.isIgnoringBatteryOptimizations(context.packageName)
}

internal fun openNotificationSettings(context: Context) {
    val notificationIntent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    openSettingsIntent(context, notificationIntent)
}

internal fun openBatterySettings(context: Context) {
    openSettingsIntent(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
}

internal fun openSettingsIntent(context: Context, intent: Intent) {
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

internal fun periodicSyncScheduled(context: Context): Boolean? {
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


