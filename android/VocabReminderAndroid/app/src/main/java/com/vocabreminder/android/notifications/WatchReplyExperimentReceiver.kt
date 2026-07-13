package com.vocabreminder.android.notifications

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import com.vocabreminder.android.BuildConfig
import com.vocabreminder.android.R
import com.vocabreminder.android.data.LocalVocabularyStore
import com.vocabreminder.android.scheduler.Wordbook
import java.time.Instant

class WatchReplyExperimentReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!BuildConfig.DEBUG) return
        when (intent.action) {
            ACTION_POST -> {
                val variant = intent.getStringExtra(EXTRA_VARIANT) ?: VARIANT_A
                val onlyAlertOnce = intent.getBooleanExtra(EXTRA_ONLY_ALERT_ONCE, false)
                postVariant(context, variant, onlyAlertOnce, "Fit3 답장 실험 $variant")
            }
            ACTION_QA_SEED_REVIEW -> seedReviewQueue(context)
            ACTION_QA_WATCH_REPLY_SETTING -> setWatchReplySetting(
                context,
                intent.getBooleanExtra(EXTRA_ENABLED, true)
            )
            ACTION_QA_INJECT_WATCH_REPLY -> injectWatchReply(
                context,
                intent.getStringExtra(EXTRA_REPLY_TEXT).orEmpty()
            )
            ACTION_REPLY -> {
                val variant = intent.getStringExtra(EXTRA_VARIANT) ?: VARIANT_A
                val reply = RemoteInput.getResultsFromIntent(intent)
                    ?.getCharSequence(RESULT_KEY)
                    ?.toString()
                    ?.trim()
                    .orEmpty()
                val record = "${Instant.now()} variant=$variant reply=$reply"
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString("last_reply", record)
                    .apply()
                Log.i(TAG, record)
                postVariant(context, variant, false, "수신: ${reply.ifBlank { "(empty)" }}")
            }
        }
    }

    private fun postVariant(
        context: Context,
        variant: String,
        onlyAlertOnce: Boolean,
        content: String
    ) {
        ReminderNotifications.ensureChannel(context)
        val builder = NotificationCompat.Builder(context, ReminderNotifications.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_review)
            .setContentTitle("Watch reply $variant")
            .setContentText(content)
            .setOnlyAlertOnce(onlyAlertOnce)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .addAction(replyAction(context, variant))

        when (variant) {
            VARIANT_B -> builder.setCategory(NotificationCompat.CATEGORY_MESSAGE)
            VARIANT_C -> {
                val user = Person.Builder().setName("Vocabulary Reminder").build()
                val sender = Person.Builder().setName("watchqc").build()
                builder
                    .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                    .setStyle(
                        NotificationCompat.MessagingStyle(user)
                            .addMessage(content, System.currentTimeMillis(), sender)
                    )
            }
        }

        NotificationManagerCompat.from(context).notify(notificationId(variant), builder.build())
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString("last_post", "${Instant.now()} variant=$variant onlyAlertOnce=$onlyAlertOnce")
            .apply()
    }

    private fun seedReviewQueue(context: Context) {
        val now = Instant.now()
        val store = LocalVocabularyStore(context)
        NotificationManagerCompat.from(context).cancelAll()
        store.clearAll()
        ReminderNotifications.clearStalePrompt(context, store)
        store.saveSettings(
            store.settings().copy(
                enableWordRecommendations = false,
                enableWatchReplyReview = true,
                updatedAt = now
            )
        )
        val cards = listOf(
            store.addCard(
                displayWord = "watchone",
                meaningKo = "워치 첫 번째 뜻",
                exampleSentence = "Watch replies handle the first review.",
                dueAt = now.minusSeconds(3),
                now = now.minusSeconds(30),
                wordbook = Wordbook.USER
            ),
            store.addCard(
                displayWord = "watchtwo",
                meaningKo = "워치 두 번째 뜻",
                exampleSentence = "A second card verifies queue advance.",
                dueAt = now.minusSeconds(2),
                now = now.minusSeconds(20),
                wordbook = Wordbook.USER
            ),
            store.addCard(
                displayWord = "watchthree",
                meaningKo = "워치 세 번째 뜻",
                exampleSentence = "A third card verifies invalid replies.",
                dueAt = now.minusSeconds(1),
                now = now.minusSeconds(10),
                wordbook = Wordbook.USER
            )
        )
        val summary = DailyReviewPlanner.planAndSchedule(context, now)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(
                "last_qa_seed",
                "${Instant.now()} cards=${cards.joinToString { it.displayWord }} scheduled=${summary.scheduledNotifications}"
            )
            .apply()
    }

    private fun setWatchReplySetting(context: Context, enabled: Boolean) {
        val store = LocalVocabularyStore(context)
        store.saveSettings(store.settings().copy(enableWatchReplyReview = enabled, updatedAt = Instant.now()))
        val activeCardId = ReminderNotifications.activePromptCardId(context)
        if (activeCardId != null) {
            store.findCard(activeCardId)?.let { ReminderNotifications.showReview(context, it) }
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString("last_qa_setting", "${Instant.now()} enableWatchReplyReview=$enabled")
            .apply()
    }

    private fun injectWatchReply(context: Context, replyText: String) {
        val activeCardId = ReminderNotifications.activePromptCardId(context)
        if (activeCardId == null || replyText.isBlank()) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString("last_qa_inject", "${Instant.now()} skipped active=$activeCardId reply=$replyText")
                .apply()
            return
        }

        val intent = Intent(context, WatchReplyReceiver::class.java)
            .setAction(WatchReplyReceiver.ACTION_WATCH_REPLY)
            .putExtra(ReminderNotifications.EXTRA_CARD_ID, activeCardId)
            .putExtra(ReminderNotifications.EXTRA_REPLY_KIND, ReminderNotifications.REPLY_KIND_REVIEW_PROMPT)
        val remoteInput = RemoteInput.Builder(ReminderNotifications.WATCH_REPLY_RESULT_KEY)
            .setLabel("qa")
            .build()
        val results = Bundle().apply {
            putCharSequence(ReminderNotifications.WATCH_REPLY_RESULT_KEY, replyText)
        }
        RemoteInput.addResultsToIntent(arrayOf(remoteInput), intent, results)
        WatchReplyReceiver().onReceive(context, intent)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString("last_qa_inject", "${Instant.now()} active=$activeCardId reply=$replyText")
            .apply()
    }

    private fun replyAction(context: Context, variant: String): NotificationCompat.Action {
        val remoteInput = RemoteInput.Builder(RESULT_KEY)
            .setLabel("reply")
            .build()
        val intent = Intent(context, WatchReplyExperimentReceiver::class.java)
            .setAction(ACTION_REPLY)
            .putExtra(EXTRA_VARIANT, variant)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            notificationId(variant) xor 0x55_000,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        )
        return NotificationCompat.Action.Builder(0, "답장", pendingIntent)
            .addRemoteInput(remoteInput)
            .setAllowGeneratedReplies(true)
            .build()
    }

    private fun notificationId(variant: String): Int {
        return when (variant) {
            VARIANT_B -> 79_002
            VARIANT_C -> 79_003
            else -> 79_001
        }
    }

    companion object {
        const val ACTION_POST = "com.vocabreminder.android.ACTION_WATCH_REPLY_EXPERIMENT_POST"
        const val ACTION_QA_SEED_REVIEW = "com.vocabreminder.android.ACTION_WATCH_REPLY_QA_SEED_REVIEW"
        const val ACTION_QA_WATCH_REPLY_SETTING = "com.vocabreminder.android.ACTION_WATCH_REPLY_QA_SETTING"
        const val ACTION_QA_INJECT_WATCH_REPLY = "com.vocabreminder.android.ACTION_WATCH_REPLY_QA_INJECT"
        private const val ACTION_REPLY = "com.vocabreminder.android.ACTION_WATCH_REPLY_EXPERIMENT_REPLY"
        const val EXTRA_VARIANT = "variant"
        const val EXTRA_ONLY_ALERT_ONCE = "only_alert_once"
        const val EXTRA_ENABLED = "enabled"
        const val EXTRA_REPLY_TEXT = "reply_text"
        private const val RESULT_KEY = "watch_reply_experiment"
        private const val PREFS_NAME = "vocab_watch_reply_experiment"
        private const val TAG = "WatchReplyExperiment"
        private const val VARIANT_A = "V-A"
        private const val VARIANT_B = "V-B"
        private const val VARIANT_C = "V-C"
    }
}
