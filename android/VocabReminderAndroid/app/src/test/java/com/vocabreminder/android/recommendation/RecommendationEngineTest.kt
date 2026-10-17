package com.vocabreminder.android.recommendation

import org.junit.Assert.assertEquals
import org.junit.Test

class RecommendationEngineTest {
    @Test
    fun normalizesFeedbackAcrossDevices() {
        assertEquals("subtle", RecommendationEngine.normalizedLemma("  ＳＵＢＴＬＥ  "))
    }
}
