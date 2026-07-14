package com.vocabreminder.android.notifications

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context.MODE_PRIVATE
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import com.vocabreminder.android.MainActivity
import com.vocabreminder.android.R
import com.vocabreminder.android.data.LocalVocabularyStore
import com.vocabreminder.android.data.VocabularyCard
import com.vocabreminder.android.scheduler.CardStatus
import com.vocabreminder.android.scheduler.ReviewResponse
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

enum class NotificationPhase {
    PROMPT,
    FEEDBACK
}

enum class NotificationFeedbackKind {
    REMEMBERED,
    FORGOTTEN,
    RECOMMENDATION
}

data class ActiveNotificationState(
    val cardId: String,
    val notificationId: Int,
    val phase: NotificationPhase,
    val feedbackKind: NotificationFeedbackKind?,
    val postedAt: Instant,
    val reminderCount: Int,
    val reminderDate: LocalDate
)

object ReminderNotifications {
    const val CHANNEL_ID = "vocabulary_review"
    const val ACTION_REVIEW = "com.vocabreminder.android.ACTION_REVIEW"
    const val ACTION_RECOMMENDATION = "com.vocabreminder.android.ACTION_RECOMMENDATION"
    const val EXTRA_CARD_ID = "card_id"
    const val EXTRA_RESPONSE = "response"
    const val EXTRA_RECOMMENDATION_ACTION = "recommendation_action"
    const val EXTRA_DISMISSED_KIND = "dismissed_kind"
    const val EXTRA_REPLY_KIND = "reply_kind"
    const val WATCH_REPLY_RESULT_KEY = "watch_reply"
    const val DISMISSED_KIND_PROMPT = "prompt"
    const val DISMISSED_KIND_FEEDBACK = "feedback"
    const val REPLY_KIND_REVIEW_PROMPT = "review_prompt"
    const val REPLY_KIND_RECOMMENDATION_PROMPT = "recommendation_prompt"
    const val REPLY_KIND_FEEDBACK = "feedback"
    const val RECOMMENDATION_START = "start"
    const val RECOMMENDATION_KNOWN = "known"
    const val RECOMMENDATION_DISMISS = "dismiss"
    private const val RECOMMENDATION_INTRO_NOTIFICATION_ID = 70_024
    private const val QUEUE_ADVANCE_REQUEST_CODE = 70_025
    private const val PROMPT_DELETE_REQUEST_CODE_OFFSET = 0x1000_0000
    private const val FEEDBACK_DELETE_REQUEST_CODE_OFFSET = 0x2000_0000
    private const val WATCH_REPLY_REQUEST_CODE_OFFSET = 0x3000_0000
    private const val PREFS_NAME = "vocab_notification_queue"
    private const val KEY_ACTIVE_PROMPT_CARD_ID = "active_prompt_card_id"
    private const val KEY_ACTIVE_PROMPT_NOTIFICATION_ID = "active_prompt_notification_id"
    private const val KEY_ACTIVE_PHASE = "active_phase"
    private const val KEY_ACTIVE_FEEDBACK_KIND = "active_feedback_kind"
    private const val KEY_ACTIVE_POSTED_AT = "active_posted_at"
    private const val KEY_ACTIVE_REMINDER_COUNT = "active_reminder_count"
    private const val KEY_ACTIVE_REMINDER_DATE = "active_reminder_date"
    private const val SUPPRESSION_PREFS_NAME = "vocab_notification_suppressions"
    private const val RECOMMENDATION_SUPPRESSION_PREFIX = "recommendation_until:"
    private val REVIEW_REPLY_CHOICES = arrayOf<CharSequence>("네", "아니오", "나중에 연락드리겠습니다")
    private val RECOMMENDATION_REPLY_CHOICES = arrayOf<CharSequence>("네", "알아요", "아니오")
    private val FEEDBACK_REPLY_CHOICES = arrayOf<CharSequence>("다음")
    private const val REVIEW_NOTIFICATION_COLOR = 0xFF4F6BED.toInt()
    private const val RECOMMENDATION_NOTIFICATION_COLOR = 0xFF00897B.toInt()
    private val notificationTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    fun ensureChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Vocabulary review",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Word recall reminders"
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun canNotify(context: Context): Boolean {
        val permissionGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!permissionGranted || !NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        val channel = context.getSystemService(NotificationManager::class.java).getNotificationChannel(CHANNEL_ID)
        if (channel?.importance == NotificationManager.IMPORTANCE_NONE) return false
        return true
    }

    fun showReview(
        context: Context,
        card: VocabularyCard,
        commandHint: String? = null,
        now: Instant = NotificationClock.now()
    ): Boolean {
        return showReviewPrompt(
            context = context,
            card = card,
            commandHint = commandHint,
            now = now,
            isReminder = false,
            forceRepublish = commandHint != null
        )
    }

    private fun showReviewPrompt(
        context: Context,
        card: VocabularyCard,
        commandHint: String?,
        now: Instant,
        isReminder: Boolean,
        forceRepublish: Boolean
    ): Boolean {
        // guide 14.4: first recall notification never exposes the meaning.
        ensureChannel(context)
        if (!canNotify(context) || isQuietHour(context, now)) return false
        val state = claimActivePrompt(context, card, now, isReminder, forceRepublish) ?: return false
        if (isReminder) cancel(context, card)
        val contentText = commandHint ?: "무슨 뜻이었지?"
        val builder = baseBuilder(context, card)
            .setContentTitle(card.displayWord)
            .setContentText(contentText)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setDeleteIntent(promptDeleteIntent(context, card))
            .addAction(action(context, card, ReviewResponse.REMEMBERED, "외움"))
            .addAction(action(context, card, ReviewResponse.FORGOTTEN, "잊음"))
            .addAction(action(context, card, ReviewResponse.UNAVAILABLE, "나중에"))
            .withWatchReply(context, card, REPLY_KIND_REVIEW_PROMPT)
        if (commandHint != null) {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText("무슨 뜻이었지?\n$commandHint"))
        }
        return show(
            context = context,
            card = card,
            builder = builder,
            now = now
        ).also { shown ->
            if (shown) {
                saveActiveState(context, state)
                UnansweredPromptReminderScheduler.schedule(context, state)
            } else if (activeNotificationState(context) == state) {
                clearActivePrompt(context, card.id)
            }
        }
    }

    fun showRememberedConfirmation(context: Context, card: VocabularyCard, now: Instant = NotificationClock.now()) {
        if (!claimFeedback(context, card, NotificationFeedbackKind.REMEMBERED, now)) return
        val nextReview = nextReviewLabel(card.dueAt, now)
        val shown = show(
            context = context,
            card = card,
            builder = baseBuilder(context, card)
                .setContentTitle("${card.displayWord} ✓")
                .setContentText("${card.meaningKo} · 다음 복습: $nextReview")
                .setStyle(NotificationCompat.BigTextStyle().bigText("${card.meaningKo}\n다음 복습: $nextReview"))
                .setAutoCancel(true)
                .setDeleteIntent(feedbackDeleteIntent(context, card))
                .withWatchReply(context, card, REPLY_KIND_FEEDBACK),
            now = now,
            respectQuietHours = false
        )
        if (!shown) cancel(context, card)
    }

    fun showForgottenMeaning(context: Context, card: VocabularyCard, now: Instant = NotificationClock.now()) {
        if (!claimFeedback(context, card, NotificationFeedbackKind.FORGOTTEN, now)) return
        val body = meaningExampleBody(card)
        val shown = show(
            context = context,
            card = card,
            builder = baseBuilder(context, card)
                .setContentTitle(card.displayWord)
                .setContentText("뜻: ${card.meaningKo}")
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setAutoCancel(true)
                .setDeleteIntent(feedbackDeleteIntent(context, card))
                .withWatchReply(context, card, REPLY_KIND_FEEDBACK),
            now = now,
            respectQuietHours = false
        )
        if (!shown) cancel(context, card)
    }

    fun showRecommendation(
        context: Context,
        card: VocabularyCard,
        commandHint: String? = null,
        now: Instant = NotificationClock.now()
    ): Boolean {
        return showRecommendationPrompt(
            context = context,
            card = card,
            commandHint = commandHint,
            now = now,
            isReminder = false,
            forceRepublish = commandHint != null
        )
    }

    private fun showRecommendationPrompt(
        context: Context,
        card: VocabularyCard,
        commandHint: String?,
        now: Instant,
        isReminder: Boolean,
        forceRepublish: Boolean
    ): Boolean {
        val prompt = if (commandHint == null) {
            "알고 있는 단어인가요?"
        } else {
            "알고 있는 단어인가요?\n$commandHint"
        }
        val body = meaningExampleBody(card, prompt = prompt)
        ensureChannel(context)
        if (!canNotify(context) || isQuietHour(context, now)) return false
        val state = claimActivePrompt(context, card, now, isReminder, forceRepublish) ?: return false
        if (isReminder) cancel(context, card)
        return show(
            context = context,
            card = card,
            builder = recommendationBuilder(context, card)
                .setContentTitle("오늘의 새 단어: ${card.displayWord}")
                .setContentText("뜻: ${card.meaningKo}")
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setDeleteIntent(promptDeleteIntent(context, card))
                .addAction(recommendationAction(context, card, RECOMMENDATION_START, "시작하기"))
                .addAction(recommendationAction(context, card, RECOMMENDATION_KNOWN, "알아요"))
                .addAction(recommendationAction(context, card, RECOMMENDATION_DISMISS, "관심 없음"))
                .withWatchReply(context, card, REPLY_KIND_RECOMMENDATION_PROMPT),
            now = now
        ).also { shown ->
            if (shown) {
                saveActiveState(context, state)
                UnansweredPromptReminderScheduler.schedule(context, state)
            } else if (activeNotificationState(context) == state) {
                clearActivePrompt(context, card.id)
            }
        }
    }

    fun showRecommendationMeaning(context: Context, card: VocabularyCard, now: Instant = NotificationClock.now()) {
        if (!claimFeedback(context, card, NotificationFeedbackKind.RECOMMENDATION, now)) return
        val body = meaningExampleBody(card)
        val shown = show(
            context = context,
            card = card,
            builder = recommendationBuilder(context, card)
                .setContentTitle(card.displayWord)
                .setContentText("뜻: ${card.meaningKo}")
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setAutoCancel(true)
                .setDeleteIntent(feedbackDeleteIntent(context, card))
                .withWatchReply(context, card, REPLY_KIND_FEEDBACK),
            now = now,
            respectQuietHours = false
        )
        if (!shown) cancel(context, card)
    }

    fun showRecommendationIntro(context: Context, now: Instant = NotificationClock.now()): Boolean {
        ensureChannel(context)
        if (!canNotify(context) || isQuietHour(context, now)) return false
        val openIntent = PendingIntent.getActivity(
            context,
            RECOMMENDATION_INTRO_NOTIFICATION_ID,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_recommendation)
            .setColor(RECOMMENDATION_NOTIFICATION_COLOR)
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentTitle("추천 단어장이 시작됐습니다")
            .setContentText("복습 자리가 남는 날, 수준에 맞는 새 단어가 소개됩니다.")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "복습 자리가 남는 날, 수준에 맞는 새 단어가 소개됩니다.\n설정에서 언제든 끌 수 있어요."
                )
            )
        NotificationManagerCompat.from(context).notify(RECOMMENDATION_INTRO_NOTIFICATION_ID, builder.build())
        return true
    }

    fun cancel(context: Context, card: VocabularyCard) {
        NotificationManagerCompat.from(context).cancel(card.notificationId)
    }

    fun cancelRecommendationIntro(context: Context) {
        NotificationManagerCompat.from(context).cancel(RECOMMENDATION_INTRO_NOTIFICATION_ID)
    }

    fun hasActivePrompt(context: Context): Boolean = activeNotificationState(context) != null

    fun activePromptCardId(context: Context): String? = activeNotificationState(context)?.cardId

    internal fun activeNotificationState(context: Context): ActiveNotificationState? {
        val prefs = context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val cardId = prefs.getString(KEY_ACTIVE_PROMPT_CARD_ID, null) ?: return null
        val notificationId = prefs.getInt(KEY_ACTIVE_PROMPT_NOTIFICATION_ID, -1)
        val phase = runCatching {
            NotificationPhase.valueOf(prefs.getString(KEY_ACTIVE_PHASE, NotificationPhase.PROMPT.name)!!)
        }.getOrDefault(NotificationPhase.PROMPT)
        val feedbackKind = prefs.getString(KEY_ACTIVE_FEEDBACK_KIND, null)?.let { raw ->
            runCatching { NotificationFeedbackKind.valueOf(raw) }.getOrNull()
        }
        val postedAtMillis = prefs.getLong(KEY_ACTIVE_POSTED_AT, -1L)
        val postedAt = if (postedAtMillis >= 0L) Instant.ofEpochMilli(postedAtMillis) else NotificationClock.now()
        val fallbackDate = postedAt.atZone(LocalVocabularyStore(context).settings().timezone).toLocalDate()
        val reminderDate = prefs.getString(KEY_ACTIVE_REMINDER_DATE, null)?.let { raw ->
            runCatching { LocalDate.parse(raw) }.getOrNull()
        } ?: fallbackDate
        val state = ActiveNotificationState(
            cardId = cardId,
            notificationId = notificationId,
            phase = phase,
            feedbackKind = feedbackKind,
            postedAt = postedAt,
            reminderCount = prefs.getInt(KEY_ACTIVE_REMINDER_COUNT, 0).coerceAtLeast(0),
            reminderDate = reminderDate
        )
        if (postedAtMillis < 0L || !prefs.contains(KEY_ACTIVE_PHASE) || !prefs.contains(KEY_ACTIVE_REMINDER_DATE)) {
            saveActiveState(context, state)
        }
        return state
    }

    fun clearActivePrompt(context: Context, cardId: String? = null) {
        val prefs = context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val activeCardId = prefs.getString(KEY_ACTIVE_PROMPT_CARD_ID, null)
        if (cardId == null || activeCardId == cardId) {
            activeCardId?.let { UnansweredPromptReminderScheduler.cancel(context, it) }
            prefs.edit()
                .remove(KEY_ACTIVE_PROMPT_CARD_ID)
                .remove(KEY_ACTIVE_PROMPT_NOTIFICATION_ID)
                .remove(KEY_ACTIVE_PHASE)
                .remove(KEY_ACTIVE_FEEDBACK_KIND)
                .remove(KEY_ACTIVE_POSTED_AT)
                .remove(KEY_ACTIVE_REMINDER_COUNT)
                .remove(KEY_ACTIVE_REMINDER_DATE)
                .apply()
        }
    }

    fun clearStalePrompt(context: Context, store: LocalVocabularyStore, now: Instant = NotificationClock.now()): Boolean {
        val state = activeNotificationState(context) ?: return false
        val card = store.allCards(includeDeleted = true).firstOrNull { it.id == state.cardId }
        val stale = card == null ||
            card.deletedAt != null ||
            card.status == CardStatus.SUSPENDED ||
            (state.phase == NotificationPhase.PROMPT && card.dueAt.isAfter(now))
        if (!stale) return false
        if (state.notificationId >= 0) {
            NotificationManagerCompat.from(context).cancel(state.notificationId)
        } else if (card != null) {
            cancel(context, card)
        }
        clearActivePrompt(context, state.cardId)
        return true
    }

    fun suppressRecommendationAfterSwipe(context: Context, cardId: String, now: Instant = NotificationClock.now()) {
        context.getSharedPreferences(SUPPRESSION_PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putLong(
                RECOMMENDATION_SUPPRESSION_PREFIX + cardId,
                now.plus(24, ChronoUnit.HOURS).toEpochMilli()
            )
            .apply()
    }

    fun isRecommendationSuppressed(context: Context, cardId: String, now: Instant = NotificationClock.now()): Boolean {
        return recommendationSuppressedUntil(context, cardId, now) != null
    }

    fun recommendationSuppressedUntil(
        context: Context,
        cardId: String,
        now: Instant = NotificationClock.now()
    ): Instant? {
        val prefs = context.getSharedPreferences(SUPPRESSION_PREFS_NAME, MODE_PRIVATE)
        val key = RECOMMENDATION_SUPPRESSION_PREFIX + cardId
        val until = prefs.getLong(key, -1L)
        if (until > now.toEpochMilli()) return Instant.ofEpochMilli(until)
        if (until >= 0L) prefs.edit().remove(key).apply()
        return null
    }

    fun pruneExpiredRecommendationSuppressions(context: Context, now: Instant = NotificationClock.now()) {
        val prefs = context.getSharedPreferences(SUPPRESSION_PREFS_NAME, MODE_PRIVATE)
        val expired = prefs.all
            .filterKeys { it.startsWith(RECOMMENDATION_SUPPRESSION_PREFIX) }
            .filterValues { (it as? Long)?.let { until -> until <= now.toEpochMilli() } != false }
            .keys
        if (expired.isEmpty()) return
        prefs.edit().apply {
            expired.forEach { remove(it) }
        }.apply()
    }

    /** Reposts the persisted single queue slot after reboot, sync, or quiet hours. */
    fun restoreActiveNotification(
        context: Context,
        store: LocalVocabularyStore = LocalVocabularyStore(context),
        now: Instant = NotificationClock.now()
    ): Boolean {
        if (clearStalePrompt(context, store, now)) return false
        val state = activeNotificationState(context) ?: return false
        if (state.phase == NotificationPhase.PROMPT &&
            QuietHoursBoundaryScheduler.isQuietHour(now, store.settings())
        ) return false
        ensureChannel(context)
        if (!canNotify(context)) return false
        if (state.phase == NotificationPhase.PROMPT) {
            UnansweredPromptReminderScheduler.schedule(context, state)
        } else {
            UnansweredPromptReminderScheduler.cancel(context, state.cardId)
        }
        if (isNotificationActive(context, state.notificationId)) return true
        val card = store.findCard(state.cardId) ?: return false
        return when (state.phase) {
            NotificationPhase.PROMPT -> {
                val asReminder = UnansweredPromptReminderScheduler.isDue(state, now) &&
                    UnansweredPromptReminderScheduler.canRemindToday(state, store.settings(), now)
                showPrompt(
                    context = context,
                    card = card,
                    now = now,
                    isReminder = asReminder,
                    forceRepublish = true
                )
            }
            NotificationPhase.FEEDBACK -> {
                when (state.feedbackKind) {
                    NotificationFeedbackKind.REMEMBERED -> showRememberedConfirmation(context, card, now)
                    NotificationFeedbackKind.FORGOTTEN -> showForgottenMeaning(context, card, now)
                    NotificationFeedbackKind.RECOMMENDATION -> showRecommendationMeaning(context, card, now)
                    null -> return false
                }
                isNotificationActive(context, card.notificationId)
            }
        }
    }

    internal fun showUnansweredPromptReminder(
        context: Context,
        card: VocabularyCard,
        now: Instant
    ): Boolean {
        return showPrompt(context, card, now, isReminder = true, forceRepublish = true)
    }

    private fun showPrompt(
        context: Context,
        card: VocabularyCard,
        now: Instant,
        isReminder: Boolean,
        forceRepublish: Boolean
    ): Boolean {
        return if (card.wordbook == com.vocabreminder.android.scheduler.Wordbook.RECOMMENDED && card.recommendationStartedAt == null) {
            showRecommendationPrompt(context, card, null, now, isReminder, forceRepublish)
        } else {
            showReviewPrompt(context, card, null, now, isReminder, forceRepublish)
        }
    }

    fun scheduleQueueAdvance(
        context: Context,
        delayMillis: Long = 0L,
        now: Instant = NotificationClock.now()
    ) {
        if (delayMillis <= 0L) {
            DailyReviewPlanner.planAndSchedule(context, now)
            return
        }
        val intent = Intent(context, NotificationQueueAdvanceReceiver::class.java)
            .setAction(NotificationQueueAdvanceReceiver.ACTION_ADVANCE_QUEUE)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            QUEUE_ADVANCE_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        context.getSystemService(AlarmManager::class.java)
            .setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + delayMillis, pendingIntent)
    }

    private fun show(
        context: Context,
        card: VocabularyCard,
        builder: NotificationCompat.Builder,
        now: Instant,
        respectQuietHours: Boolean = true
    ): Boolean {
        ensureChannel(context)
        if (!canNotify(context) || (respectQuietHours && isQuietHour(context, now))) return false
        NotificationManagerCompat.from(context).notify(card.notificationId, builder.build())
        return true
    }

    private fun isQuietHour(context: Context, now: Instant): Boolean {
        return QuietHoursBoundaryScheduler.isQuietHour(now, LocalVocabularyStore(context).settings())
    }

    private fun baseBuilder(context: Context, card: VocabularyCard): NotificationCompat.Builder {
        val openIntent = PendingIntent.getActivity(
            context,
            card.notificationId,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_review)
            .setColor(REVIEW_NOTIFICATION_COLOR)
            .setContentIntent(openIntent)
            .setAutoCancel(false)
            // WorkManager and AlarmManager intentionally race for reliability. Updating the
            // same notification must never produce a second sound/vibration.
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
    }

    private fun recommendationBuilder(context: Context, card: VocabularyCard): NotificationCompat.Builder {
        return baseBuilder(context, card)
            .setSmallIcon(R.drawable.ic_notification_recommendation)
            .setColor(RECOMMENDATION_NOTIFICATION_COLOR)
    }

    private fun promptDeleteIntent(context: Context, card: VocabularyCard): PendingIntent {
        return queueAdvanceDeleteIntent(
            context = context,
            card = card,
            dismissedKind = DISMISSED_KIND_PROMPT,
            requestCodeOffset = PROMPT_DELETE_REQUEST_CODE_OFFSET
        )
    }

    private fun feedbackDeleteIntent(context: Context, card: VocabularyCard): PendingIntent {
        return queueAdvanceDeleteIntent(
            context = context,
            card = card,
            dismissedKind = DISMISSED_KIND_FEEDBACK,
            requestCodeOffset = FEEDBACK_DELETE_REQUEST_CODE_OFFSET
        )
    }

    private fun queueAdvanceDeleteIntent(
        context: Context,
        card: VocabularyCard,
        dismissedKind: String,
        requestCodeOffset: Int
    ): PendingIntent {
        val intent = Intent(context, NotificationQueueAdvanceReceiver::class.java)
            .setAction(NotificationQueueAdvanceReceiver.ACTION_ADVANCE_QUEUE)
            .putExtra(EXTRA_CARD_ID, card.id)
            .putExtra(EXTRA_DISMISSED_KIND, dismissedKind)
        return PendingIntent.getBroadcast(
            context,
            card.notificationId xor requestCodeOffset,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun meaningExampleBody(card: VocabularyCard, prompt: String? = null): CharSequence {
        val builder = SpannableStringBuilder("뜻: ${card.meaningKo}")
        if (card.exampleSentence.isNotBlank()) {
            val exampleStart = builder.length
            builder.append("\n예문: ${card.exampleSentence}")
            builder.setSpan(
                StyleSpan(Typeface.ITALIC),
                exampleStart,
                builder.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        if (prompt != null) {
            builder.append("\n$prompt")
        }
        return builder
    }

    private fun nextReviewLabel(dueAt: Instant, now: Instant): String {
        val zone = ZoneId.systemDefault()
        val due = dueAt.atZone(zone)
        val days = ChronoUnit.DAYS.between(now.atZone(zone).toLocalDate(), due.toLocalDate())
        return when {
            days < 0L -> "지금"
            days == 0L -> "오늘 ${due.format(notificationTimeFormatter)}"
            days == 1L -> "내일 ${due.format(notificationTimeFormatter)}"
            else -> "${days}일 뒤"
        }
    }

    private fun NotificationCompat.Builder.withWatchReply(
        context: Context,
        card: VocabularyCard,
        replyKind: String
    ): NotificationCompat.Builder {
        if (!watchReplyEnabled(context)) return this
        return extend(NotificationCompat.WearableExtender().addAction(watchReplyAction(context, card, replyKind)))
    }

    private fun watchReplyAction(
        context: Context,
        card: VocabularyCard,
        replyKind: String
    ): NotificationCompat.Action {
        val choices = watchReplyChoices(replyKind)
        val remoteInput = RemoteInput.Builder(WATCH_REPLY_RESULT_KEY)
            .setLabel(choices.joinToString("/"))
            .setChoices(choices)
            .build()
        val intent = Intent(context, WatchReplyReceiver::class.java)
            .setAction(WatchReplyReceiver.ACTION_WATCH_REPLY)
            .putExtra(EXTRA_CARD_ID, card.id)
            .putExtra(EXTRA_REPLY_KIND, replyKind)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            card.notificationId xor WATCH_REPLY_REQUEST_CODE_OFFSET xor replyKind.hashCode(),
            intent,
            watchReplyPendingIntentFlags()
        )
        return NotificationCompat.Action.Builder(0, "답장", pendingIntent)
            .addRemoteInput(remoteInput)
            .setAllowGeneratedReplies(true)
            .build()
    }

    private fun watchReplyChoices(replyKind: String): Array<CharSequence> {
        return when (replyKind) {
            REPLY_KIND_REVIEW_PROMPT -> REVIEW_REPLY_CHOICES
            REPLY_KIND_RECOMMENDATION_PROMPT -> RECOMMENDATION_REPLY_CHOICES
            else -> FEEDBACK_REPLY_CHOICES
        }
    }

    private fun watchReplyEnabled(context: Context): Boolean {
        return LocalVocabularyStore(context).settings().enableWatchReplyReview
    }

    private fun watchReplyPendingIntentFlags(): Int {
        return PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
    }

    @Synchronized
    private fun claimActivePrompt(
        context: Context,
        card: VocabularyCard,
        now: Instant,
        isReminder: Boolean,
        forceRepublish: Boolean
    ): ActiveNotificationState? {
        val existing = activeNotificationState(context)
        if (existing != null && existing.cardId != card.id) return null
        if (existing?.phase == NotificationPhase.FEEDBACK) return null
        if (existing != null && !isReminder && !forceRepublish) return null

        val today = now.atZone(LocalVocabularyStore(context).settings().timezone).toLocalDate()
        val previousCount = if (existing?.reminderDate == today) existing?.reminderCount ?: 0 else 0
        if (isReminder && previousCount >= UnansweredPromptReminderScheduler.MAX_REMINDERS_PER_DAY) return null
        val claimed = if (existing != null && !isReminder) {
            // A reboot/quiet-hours restore must not restart the four-hour clock.
            existing.copy(notificationId = card.notificationId)
        } else {
            ActiveNotificationState(
                cardId = card.id,
                notificationId = card.notificationId,
                phase = NotificationPhase.PROMPT,
                feedbackKind = null,
                postedAt = now,
                reminderCount = previousCount + if (isReminder) 1 else 0,
                reminderDate = today
            )
        }
        // Reserve the single queue slot before notify(); AlarmManager and WorkManager can
        // arrive concurrently on different threads.
        saveActiveState(context, claimed)
        return claimed
    }

    @Synchronized
    private fun claimFeedback(
        context: Context,
        card: VocabularyCard,
        kind: NotificationFeedbackKind,
        now: Instant
    ): Boolean {
        val existing = activeNotificationState(context)
        if (existing != null && existing.cardId != card.id) return false
        val today = now.atZone(LocalVocabularyStore(context).settings().timezone).toLocalDate()
        saveActiveState(
            context,
            ActiveNotificationState(
                cardId = card.id,
                notificationId = card.notificationId,
                phase = NotificationPhase.FEEDBACK,
                feedbackKind = kind,
                postedAt = now,
                reminderCount = if (existing?.reminderDate == today) existing?.reminderCount ?: 0 else 0,
                reminderDate = today
            )
        )
        UnansweredPromptReminderScheduler.cancel(context, card.id)
        return true
    }

    internal fun saveActiveState(context: Context, state: ActiveNotificationState) {
        context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putString(KEY_ACTIVE_PROMPT_CARD_ID, state.cardId)
            .putInt(KEY_ACTIVE_PROMPT_NOTIFICATION_ID, state.notificationId)
            .putString(KEY_ACTIVE_PHASE, state.phase.name)
            .apply {
                if (state.feedbackKind == null) remove(KEY_ACTIVE_FEEDBACK_KIND)
                else putString(KEY_ACTIVE_FEEDBACK_KIND, state.feedbackKind.name)
            }
            .putLong(KEY_ACTIVE_POSTED_AT, state.postedAt.toEpochMilli())
            .putInt(KEY_ACTIVE_REMINDER_COUNT, state.reminderCount)
            .putString(KEY_ACTIVE_REMINDER_DATE, state.reminderDate.toString())
            .apply()
    }

    private fun isNotificationActive(context: Context, notificationId: Int): Boolean {
        if (notificationId < 0) return false
        return context.getSystemService(NotificationManager::class.java)
            .activeNotifications
            .any { it.id == notificationId }
    }

    private fun action(
        context: Context,
        card: VocabularyCard,
        response: ReviewResponse,
        label: String
    ): NotificationCompat.Action {
        val intent = Intent(context, ReviewActionReceiver::class.java)
            .setAction(ACTION_REVIEW)
            .putExtra(EXTRA_CARD_ID, card.id)
            .putExtra(EXTRA_RESPONSE, response.name)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            card.notificationId + response.ordinal,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Action.Builder(0, label, pendingIntent).build()
    }

    private fun recommendationAction(
        context: Context,
        card: VocabularyCard,
        recommendationAction: String,
        label: String
    ): NotificationCompat.Action {
        val intent = Intent(context, RecommendationActionReceiver::class.java)
            .setAction(ACTION_RECOMMENDATION)
            .putExtra(EXTRA_CARD_ID, card.id)
            .putExtra(EXTRA_RECOMMENDATION_ACTION, recommendationAction)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            card.notificationId + recommendationAction.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Action.Builder(0, label, pendingIntent).build()
    }
}
