package com.vocabreminder.android.data

import android.content.Context
import com.vocabreminder.android.scheduler.CardStatus
import com.vocabreminder.android.scheduler.ReviewEventDraft
import com.vocabreminder.android.scheduler.ReviewResponse
import com.vocabreminder.android.scheduler.SchedulerCard
import com.vocabreminder.android.scheduler.Wordbook
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

data class VocabularyCard(
    val id: String,
    val lemma: String,
    val displayWord: String,
    val meaningKo: String,
    val definitionEn: String = "",
    val exampleSentence: String = "",
    val status: CardStatus,
    val dueAt: Instant,
    val intervalDays: Int,
    val lapseCount: Int,
    val intervalBeforeLapse: Int? = null,
    val notificationId: Int,
    val createdAt: Instant,
    val updatedAt: Instant
) {
    fun toSchedulerCard(): SchedulerCard {
        return SchedulerCard(
            id = id,
            status = status,
            intervalDays = intervalDays,
            dueAt = dueAt,
            lapseCount = lapseCount,
            intervalBeforeLapse = intervalBeforeLapse,
            meaningKo = meaningKo,
            wordbook = Wordbook.USER
        )
    }

    fun withSchedulerCard(card: SchedulerCard, now: Instant): VocabularyCard {
        return copy(
            status = card.status,
            dueAt = card.dueAt,
            intervalDays = card.intervalDays,
            lapseCount = card.lapseCount,
            intervalBeforeLapse = card.intervalBeforeLapse,
            updatedAt = now
        )
    }
}

data class StoredReviewEvent(
    val id: String,
    val cardId: String,
    val response: ReviewResponse,
    val previousStatus: CardStatus,
    val nextStatus: CardStatus,
    val previousDueAt: Instant,
    val nextDueAt: Instant,
    val previousIntervalDays: Int,
    val nextIntervalDays: Int,
    val clientCreatedAt: Instant
)

class LocalVocabularyStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("vocab_reminder_store", Context.MODE_PRIVATE)

    fun cards(): List<VocabularyCard> {
        val array = JSONArray(prefs.getString(KEY_CARDS, "[]"))
        return (0 until array.length()).map { parseCard(array.getJSONObject(it)) }
            .sortedBy { it.createdAt }
    }

    fun events(): List<StoredReviewEvent> {
        val array = JSONArray(prefs.getString(KEY_EVENTS, "[]"))
        return (0 until array.length()).map { parseEvent(array.getJSONObject(it)) }
            .sortedBy { it.clientCreatedAt }
    }

    fun clearAll() {
        prefs.edit().clear().commit()
    }

    fun findCard(id: String): VocabularyCard? {
        return cards().firstOrNull { it.id == id }
    }

    fun addCard(
        displayWord: String,
        meaningKo: String,
        exampleSentence: String,
        dueAt: Instant,
        now: Instant
    ): VocabularyCard {
        val normalizedWord = displayWord.trim()
        val existing = cards().firstOrNull { it.lemma.equals(normalizedWord.lowercase(), ignoreCase = true) }
        if (existing != null) {
            return existing
        }

        val id = UUID.randomUUID().toString()
        val card = VocabularyCard(
            id = id,
            lemma = normalizedWord.lowercase(),
            displayWord = normalizedWord,
            meaningKo = meaningKo.trim(),
            exampleSentence = exampleSentence.trim(),
            status = CardStatus.NEW,
            dueAt = dueAt,
            intervalDays = 0,
            lapseCount = 0,
            notificationId = id.hashCode() and 0x7fffffff,
            createdAt = now,
            updatedAt = now
        )
        saveCards(cards() + card)
        return card
    }

    fun updateCard(card: VocabularyCard) {
        saveCards(cards().map { if (it.id == card.id) card else it })
    }

    fun appendEvent(event: ReviewEventDraft, now: Instant) {
        val stored = StoredReviewEvent(
            id = UUID.randomUUID().toString(),
            cardId = event.cardId,
            response = event.response,
            previousStatus = event.previousStatus,
            nextStatus = event.nextStatus,
            previousDueAt = event.previousDueAt,
            nextDueAt = event.nextDueAt,
            previousIntervalDays = event.previousIntervalDays,
            nextIntervalDays = event.nextIntervalDays,
            clientCreatedAt = now
        )
        saveEvents(events() + stored)
    }

    fun recentResponses(cardId: String, limit: Int): List<ReviewResponse> {
        return events()
            .filter { it.cardId == cardId }
            .takeLast(limit)
            .map { it.response }
    }

    fun unavailableCountToday(cardId: String, zoneId: ZoneId, now: Instant): Int {
        val today = now.atZone(zoneId).toLocalDate()
        return events().count {
            it.cardId == cardId &&
                it.response == ReviewResponse.UNAVAILABLE &&
                it.clientCreatedAt.atZone(zoneId).toLocalDate() == today
        }
    }

    private fun saveCards(cards: List<VocabularyCard>) {
        val array = JSONArray()
        cards.forEach { array.put(it.toJson()) }
        prefs.edit().putString(KEY_CARDS, array.toString()).apply()
    }

    private fun saveEvents(events: List<StoredReviewEvent>) {
        val array = JSONArray()
        events.forEach { array.put(it.toJson()) }
        prefs.edit().putString(KEY_EVENTS, array.toString()).apply()
    }

    private fun VocabularyCard.toJson(): JSONObject {
        return JSONObject()
            .put("id", id)
            .put("lemma", lemma)
            .put("display_word", displayWord)
            .put("meaning_ko", meaningKo)
            .put("definition_en", definitionEn)
            .put("example_sentence", exampleSentence)
            .put("status", status.name)
            .put("due_at", dueAt.toString())
            .put("interval_days", intervalDays)
            .put("lapse_count", lapseCount)
            .put("interval_before_lapse", intervalBeforeLapse)
            .put("notification_id", notificationId)
            .put("created_at", createdAt.toString())
            .put("updated_at", updatedAt.toString())
    }

    private fun StoredReviewEvent.toJson(): JSONObject {
        return JSONObject()
            .put("id", id)
            .put("card_id", cardId)
            .put("response", response.name)
            .put("previous_status", previousStatus.name)
            .put("next_status", nextStatus.name)
            .put("previous_due_at", previousDueAt.toString())
            .put("next_due_at", nextDueAt.toString())
            .put("previous_interval_days", previousIntervalDays)
            .put("next_interval_days", nextIntervalDays)
            .put("client_created_at", clientCreatedAt.toString())
    }

    private fun parseCard(json: JSONObject): VocabularyCard {
        return VocabularyCard(
            id = json.getString("id"),
            lemma = json.getString("lemma"),
            displayWord = json.getString("display_word"),
            meaningKo = json.getString("meaning_ko"),
            definitionEn = json.optString("definition_en"),
            exampleSentence = json.optString("example_sentence"),
            status = CardStatus.valueOf(json.getString("status")),
            dueAt = Instant.parse(json.getString("due_at")),
            intervalDays = json.getInt("interval_days"),
            lapseCount = json.getInt("lapse_count"),
            intervalBeforeLapse = if (json.isNull("interval_before_lapse")) null else json.getInt("interval_before_lapse"),
            notificationId = json.getInt("notification_id"),
            createdAt = Instant.parse(json.getString("created_at")),
            updatedAt = Instant.parse(json.getString("updated_at"))
        )
    }

    private fun parseEvent(json: JSONObject): StoredReviewEvent {
        return StoredReviewEvent(
            id = json.getString("id"),
            cardId = json.getString("card_id"),
            response = ReviewResponse.valueOf(json.getString("response")),
            previousStatus = CardStatus.valueOf(json.getString("previous_status")),
            nextStatus = CardStatus.valueOf(json.getString("next_status")),
            previousDueAt = Instant.parse(json.getString("previous_due_at")),
            nextDueAt = Instant.parse(json.getString("next_due_at")),
            previousIntervalDays = json.getInt("previous_interval_days"),
            nextIntervalDays = json.getInt("next_interval_days"),
            clientCreatedAt = Instant.parse(json.getString("client_created_at"))
        )
    }

    private companion object {
        const val KEY_CARDS = "cards"
        const val KEY_EVENTS = "review_events"
    }
}
