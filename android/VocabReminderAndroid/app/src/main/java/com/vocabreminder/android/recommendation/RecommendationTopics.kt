package com.vocabreminder.android.recommendation

/** Local vocabulary-only signals. No captured text is sent to a provider. */
object RecommendationTopics {
    private val keywords = mapOf(
        "academic" to setOf("research", "theory", "evidence", "hypothesis", "literature", "concept", "논문", "연구", "이론"),
        "methods" to setOf("experiment", "protocol", "measurement", "sample", "replicate", "procedure", "participant", "실험", "측정", "방법"),
        "statistics" to setOf("regression", "variance", "probability", "bayesian", "distribution", "correlation", "estimate", "통계", "확률", "회귀"),
        "neuroscience" to setOf("brain", "neural", "neuron", "cortex", "cognitive", "memory", "attention", "뇌", "신경", "인지"),
        "general" to setOf("travel", "work", "family", "city", "food", "conversation", "생활", "여행", "일상")
    )
    fun matches(context: String): Set<String> {
        val tokens = context.lowercase(java.util.Locale.ROOT).split(Regex("[^\\p{L}]+" )).toSet()
        return keywords.filterValues { terms -> terms.any { it in tokens } }.keys
    }
}
