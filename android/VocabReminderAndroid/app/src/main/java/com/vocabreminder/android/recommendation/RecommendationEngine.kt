package com.vocabreminder.android.recommendation

import com.vocabreminder.android.data.LocalVocabularyStore
import com.vocabreminder.android.scheduler.SchedulerSettings
import java.time.Instant
import java.text.Normalizer
import java.util.Locale

/** Recommendation content and selection belong exclusively to the computer server. */
object RecommendationEngine {
    @Suppress("UNUSED_PARAMETER")
    fun ensureRecommendations(store: LocalVocabularyStore, settings: SchedulerSettings, now: Instant): Int = 0

    @Suppress("UNUSED_PARAMETER")
    fun reason(lemma: String, contexts: List<String>): String = "컴퓨터에서 준비한 추천"

    fun normalizedLemma(lemma: String): String =
        Normalizer.normalize(lemma, Normalizer.Form.NFKC).trim().lowercase(Locale.ROOT)
}
