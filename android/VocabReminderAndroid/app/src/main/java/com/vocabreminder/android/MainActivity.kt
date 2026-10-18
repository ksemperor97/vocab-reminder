package com.vocabreminder.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.vocabreminder.android.capture.CaptureMethod
import com.vocabreminder.android.capture.CaptureProvenanceExtractor
import com.vocabreminder.android.notifications.QuietHoursBoundaryScheduler
import com.vocabreminder.android.notifications.QuietHoursWorker
import com.vocabreminder.android.notifications.ReminderNotifications

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ReminderNotifications.ensureChannel(this)
        QuietHoursWorker.ensureScheduled(this)
        QuietHoursBoundaryScheduler.schedule(this)

        val initialCapture = captureFromIntent(intent)

        setContent { VocabReminderApp(initialCapture) }
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
            Intent.ACTION_VIEW -> intent.data
                ?.takeIf { it.scheme == "vocabreminder" && it.host == "pair" }
                ?.let { link -> InitialCapture(pairServer = link.getQueryParameter("server").orEmpty(), pairCode = link.getQueryParameter("code").orEmpty()) }
                ?: InitialCapture()
            else -> InitialCapture()
        }
    }
}

