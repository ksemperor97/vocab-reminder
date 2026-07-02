package com.vocabreminder.android.capture

import com.vocabreminder.android.dictionary.DictionaryResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureDictionaryTest {
    @Test
    fun sentenceWithFourOrMoreWordsRequiresCandidateSelection() {
        val analysis = CaptureNormalizer.analyze("The results are tentative, but the pattern is robust.")

        assertTrue(analysis.needsWordSelection)
        assertTrue(analysis.candidates.contains("tentative"))
        assertTrue(analysis.candidates.contains("robust"))
    }

    @Test
    fun localResolverNormalizesFallbackLemma() {
        val analysis = CaptureNormalizer.analyze("Robustly,")
        val result = DictionaryResolver.resolve(analysis, sourceApp = "test")

        assertEquals("robust", result.lemma)
        assertEquals("견고한, 안정적인", result.meaningKo)
        assertEquals("local_dictionary", result.sourceType)
    }

    @Test
    fun unknownWordCanStillBeSavedWithoutMeaning() {
        val result = DictionaryResolver.resolve("perovskite")

        assertEquals("perovskite", result.lemma)
        assertEquals("", result.meaningKo)
        assertEquals("user", result.sourceType)
    }
}
