package com.vocabreminder.android.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.vocabreminder.android.data.LocalVocabularyStore
import java.time.Instant

class RecommendationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ReminderNotifications.ACTION_RECOMMENDATION) return

        val cardId = intent.getStringExtra(ReminderNotifications.EXTRA_CARD_ID) ?: return
        val action = intent.getStringExtra(ReminderNotifications.EXTRA_RECOMMENDATION_ACTION) ?: return
        val store = LocalVocabularyStore(context)
        val card = store.findCard(cardId) ?: return
        val now = Instant.now()

        when (action) {
            ReminderNotifications.RECOMMENDATION_START -> {
                // guide 24.3: [시작하기] moves the recommendation into the normal review cycle.
                store.markRecommendationStarted(card.id, now)
                val updated = store.findCard(card.id) ?: return
                ReminderNotifications.cancel(context, card)
                ReminderScheduler.schedule(context, updated, now)
            }
            ReminderNotifications.RECOMMENDATION_KNOWN -> {
                store.softDeleteRecommendation(card.id, "known", now)
                ReminderNotifications.cancel(context, card)
            }
            ReminderNotifications.RECOMMENDATION_DISMISS -> {
                store.softDeleteRecommendation(card.id, "dismissed", now)
                ReminderNotifications.cancel(context, card)
            }
        }
    }
}
