package com.vocabreminder.android.data

import com.vocabreminder.android.BuildConfig
import com.vocabreminder.android.capture.CaptureMethod
import com.vocabreminder.android.scheduler.CardStatus
import com.vocabreminder.android.scheduler.ReviewResponse
import com.vocabreminder.android.scheduler.SchedulerCard
import com.vocabreminder.android.scheduler.SchedulerSettings
import com.vocabreminder.android.scheduler.Wordbook
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

data class VocabularyCard(
    val id: String,
    val lemma: String,
    val displayWord: String,
    val meaningKo: String,
    val definitionEn: String = "",
    val partOfSpeech: String = "",
    val phonetic: String = "",
    val exampleSentence: String = "",
    val sourceAppPrimary: String = "",
    val sourceUrlPrimary: String = "",
    val definitionSourcePrimary: String = "user",
    val primaryDefinitionCandidateId: String? = null,
    val status: CardStatus,
    val wordbook: Wordbook = Wordbook.USER,
    val dueAt: Instant,
    val intervalDays: Int,
    val lapseCount: Int,
    val intervalBeforeLapse: Int? = null,
    val recommendationStartedAt: Instant? = null,
    val deletedAt: Instant? = null,
    val notificationId: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
    val version: Int = 1,
    // Field-group clocks (sync protocol 3). Local writes stamp them in
    // CardMerge.stamp; see shared/test-vectors/card_merge.json.
    val contentVersion: Int = version,
    val contentUpdatedAt: Instant = updatedAt,
    val scheduleVersion: Int = version,
    val scheduleUpdatedAt: Instant = updatedAt,
    val mergedInto: String? = null,
    val createdDeviceId: String = ""
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
            wordbook = wordbook,
            recommendationStartedAt = recommendationStartedAt,
            deletedAt = deletedAt
        )
    }

    fun withSchedulerCard(card: SchedulerCard, now: Instant): VocabularyCard {
        return copy(
            status = card.status,
            dueAt = card.dueAt,
            intervalDays = card.intervalDays,
            lapseCount = card.lapseCount,
            intervalBeforeLapse = card.intervalBeforeLapse,
            updatedAt = now,
            version = version + 1
        )
    }
}

data class StoredContext(
    val id: String,
    val cardId: String,
    val sourceSentence: String,
    val sourceUrl: String,
    val sourceApp: String,
    val pageTitle: String = "",
    val captureMethod: CaptureMethod,
    val createdAt: Instant
)

data class StoredDefinitionCandidate(
    val id: String,
    val cardId: String,
    val source: String,
    val rawText: String,
    val parsedMeaningKo: String,
    val parsedDefinitionEn: String,
    val partOfSpeech: String,
    val phonetic: String,
    val exampleSentence: String,
    val confidence: Double,
    val selectedAsPrimary: Boolean,
    val createdAt: Instant,
    val originDeviceId: String? = null,
    val baseCardVersion: Int? = null
)

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
    val clientCreatedAt: Instant,
    val metadata: Map<String, String> = emptyMap()
)

data class RecommendationFeedback(
    val id: String,
    val cardId: String,
    val lemma: String,
    val action: String,
    val createdAt: Instant
)

data class UserSettingsData(
    val dailyWordGoal: Int = 10,
    val maxQueueSize: Int = 20,
    val snoozeDurationMinutes: Long = 60,
    val quietHoursStart: LocalTime = LocalTime.of(23, 0),
    val quietHoursEnd: LocalTime = LocalTime.of(9, 0),
    val timezone: ZoneId = ZoneId.of("Asia/Seoul"),
    val enableWordRecommendations: Boolean = true,
    val enableWatchReplyReview: Boolean = true,
    val recommendationsIntroducedAt: Instant? = null,
    val updatedAt: Instant = Instant.EPOCH
) {
    fun toSchedulerSettings(): SchedulerSettings {
        return SchedulerSettings(
            dailyWordGoal = dailyWordGoal,
            maxQueueSize = maxQueueSize,
            snoozeDurationMinutes = snoozeDurationMinutes,
            quietHoursStart = quietHoursStart,
            quietHoursEnd = quietHoursEnd,
            timezone = timezone,
            enableWordRecommendations = enableWordRecommendations
        )
    }
}

data class CloudSyncCredentials(
    val serverBaseUrl: String = BuildConfig.DEFAULT_SYNC_SERVER_URL,
    val bearerToken: String = BuildConfig.DEFAULT_SYNC_BEARER_TOKEN
)

data class CloudSyncStatus(
    val lastSuccessAt: Instant? = null,
    val lastFailureAt: Instant? = null,
    val lastError: String = "",
    val lastSkippedSummary: String = "",
    val lastRequestId: String = "",
    val lastWorkerRunAt: Instant? = null
)

data class CloudSyncResult(
    val snapshotJson: String,
    val skippedSummary: String,
    val requestId: String? = null
)

data class CardEditResult(
    val before: VocabularyCard,
    val after: VocabularyCard
) {
    val filledMissingMeaning: Boolean = before.meaningKo.isBlank() && after.meaningKo.isNotBlank()
}
