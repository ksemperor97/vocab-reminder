package com.vocabreminder.android.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.vocabreminder.android.MainActivity
import com.vocabreminder.android.R
import com.vocabreminder.android.data.VocabularyCard
import com.vocabreminder.android.scheduler.ReviewResponse

object ReminderNotifications {
    const val CHANNEL_ID = "vocabulary_review"
    const val ACTION_REVIEW = "com.vocabreminder.android.ACTION_REVIEW"
    const val EXTRA_CARD_ID = "card_id"
    const val EXTRA_RESPONSE = "response"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
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
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    fun showReview(context: Context, card: VocabularyCard) {
        // guide 14.4: first recall notification never exposes the meaning.
        show(
            context = context,
            card = card,
            builder = baseBuilder(context, card)
                .setContentTitle(card.displayWord)
                .setContentText("무슨 뜻이었지?")
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .addAction(action(context, card, ReviewResponse.REMEMBERED, "외웠다"))
                .addAction(action(context, card, ReviewResponse.FORGOTTEN, "못외웠다"))
                .addAction(action(context, card, ReviewResponse.UNAVAILABLE, "나중에 하기"))
        )
    }

    fun showRememberedConfirmation(context: Context, card: VocabularyCard) {
        show(
            context = context,
            card = card,
            builder = baseBuilder(context, card)
                .setContentTitle("${card.displayWord} ✓")
                .setContentText(card.meaningKo)
                .setStyle(NotificationCompat.BigTextStyle().bigText("${card.meaningKo}\n다음 복습: ${card.dueAt}"))
                .setTimeoutAfter(5_000)
        )
    }

    fun showForgottenMeaning(context: Context, card: VocabularyCard) {
        val body = buildString {
            append("뜻: ${card.meaningKo}")
            if (card.exampleSentence.isNotBlank()) {
                append("\n예문: ${card.exampleSentence}")
            }
        }
        show(
            context = context,
            card = card,
            builder = baseBuilder(context, card)
                .setContentTitle(card.displayWord)
                .setContentText("뜻: ${card.meaningKo}")
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        )
    }

    fun cancel(context: Context, card: VocabularyCard) {
        NotificationManagerCompat.from(context).cancel(card.notificationId)
    }

    private fun show(context: Context, card: VocabularyCard, builder: NotificationCompat.Builder) {
        ensureChannel(context)
        if (!canNotify(context)) return
        NotificationManagerCompat.from(context).notify(card.notificationId, builder.build())
    }

    private fun baseBuilder(context: Context, card: VocabularyCard): NotificationCompat.Builder {
        val openIntent = PendingIntent.getActivity(
            context,
            card.notificationId,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openIntent)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
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
}
