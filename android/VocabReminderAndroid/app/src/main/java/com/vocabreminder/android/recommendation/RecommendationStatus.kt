package com.vocabreminder.android.recommendation

import com.vocabreminder.android.data.LocalVocabularyStore
import com.vocabreminder.android.notifications.DailyReviewPlanner
import com.vocabreminder.android.scheduler.CardStatus
import com.vocabreminder.android.scheduler.Wordbook
import java.time.Instant

/** User-facing explanation based on the same budget and eligibility as the planner. */
object RecommendationStatus {
    fun describe(store: LocalVocabularyStore, now: Instant = Instant.now()): String {
        val settings = store.settings()
        if (!settings.enableWordRecommendations) return "추천이 꺼져 있습니다. 설정에서 추천 단어를 켜세요."
        val remaining = (settings.dailyWordGoal - DailyReviewPlanner.processedCardIdsToday(store, settings.timezone, now).size).coerceAtLeast(0)
        if (remaining == 0) return "오늘 학습 목표를 완료했습니다. 내일 새 추천을 준비합니다."
        val eligible = store.cards().filter {
            it.meaningKo.isNotBlank() && it.status != CardStatus.MASTERED && it.status != CardStatus.SUSPENDED
        }
        val due = eligible.count { (it.wordbook == Wordbook.USER || it.recommendationStartedAt != null) && !it.dueAt.isAfter(now) }
        if (due >= minOf(remaining, settings.maxQueueSize)) return "기존 단어 복습을 먼저 진행합니다. 여유가 생기면 추천합니다."
        val waiting = eligible.count { it.wordbook == Wordbook.RECOMMENDED && it.recommendationStartedAt == null }
        if (waiting > 0) return "추천 ${waiting}개가 준비되어 있습니다. 잠자는 시간에는 알림을 쉬어요."
        return "컴퓨터에서 새 추천을 준비합니다. 동기화하면 받아올 수 있어요."
    }
}
