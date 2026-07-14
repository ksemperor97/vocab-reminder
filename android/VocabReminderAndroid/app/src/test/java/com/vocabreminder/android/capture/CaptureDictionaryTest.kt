package com.vocabreminder.android.capture

import com.vocabreminder.android.dictionary.DictionaryResolver
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CaptureDictionaryTest {
    @Test
    fun sharedCaptureNormalizationVectorsPass() {
        val vectors = JSONArray(readCaptureVectors())
        assertTrue("Expected capture vectors", vectors.length() >= 3)

        for (index in 0 until vectors.length()) {
            val vector = vectors.getJSONObject(index)
            val analysis = CaptureNormalizer.analyze(vector.getString("raw"))
            val then = vector.getJSONObject("then")
            if (then.has("needs_word_selection")) {
                assertEquals(vector.getString("name"), then.getBoolean("needs_word_selection"), analysis.needsWordSelection)
            }
            if (then.has("candidates")) {
                assertEquals(vector.getString("name"), readStringList(then.getJSONArray("candidates")), analysis.candidates)
            }
            if (then.has("display_word")) {
                assertEquals(vector.getString("name"), then.getString("display_word"), analysis.displayWord)
            }
            if (then.has("lemma")) {
                assertEquals(vector.getString("name"), then.getString("lemma"), analysis.lemma)
            }
            assertCandidateIncludes(vector, then, analysis)
            assertCandidateExcludes(vector, then, analysis)
        }
    }

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
    fun selectedPastTenseCandidateKeepsSilentE() {
        val analysis = CaptureNormalizer.analyzeSelection("The protocol mitigated measurement noise.", "mitigated")
        val result = DictionaryResolver.resolve(analysis, sourceApp = "test")

        assertEquals("mitigate", result.lemma)
        assertEquals("완화하다, 줄이다", result.meaningKo)
        assertEquals("local_dictionary", result.sourceType)
    }

    @Test
    fun inflectedLookingResolvesToLookMeaning() {
        val result = DictionaryResolver.resolve("looking", sourceApp = "test")

        assertEquals("look", result.lemma)
        assertEquals("보다, 바라보다; 찾아보다", result.meaningKo)
    }

    @Test
    fun behemothResolvesToLocalMeaning() {
        val result = DictionaryResolver.resolve("behemoth", sourceApp = "test")

        assertEquals("behemoth", result.lemma)
        assertEquals("거대한 것, 거대한 짐승", result.meaningKo)
        assertEquals("local_dictionary", result.sourceType)
    }

    @Test
    fun resilientResolvesToKoreanMeaning() {
        val result = DictionaryResolver.resolve("resilient", sourceApp = "test")

        assertEquals("resilient", result.lemma)
        assertEquals("회복력 있는, 탄력 있는", result.meaningKo)
        assertEquals("local_dictionary", result.sourceType)
    }

    @Test
    fun generatedRecommendationCatalogAlsoBacksCaptureLookup() {
        val result = DictionaryResolver.resolve("protocol", sourceApp = "test")

        assertEquals("protocol", result.lemma)
        assertEquals("절차, 실험 계획", result.meaningKo)
        assertEquals("local_dictionary", result.sourceType)
    }

    @Test
    fun unknownWordCanStillBeSavedWithoutMeaning() {
        val result = DictionaryResolver.resolve("perovskite")

        assertEquals("perovskite", result.lemma)
        assertEquals("", result.meaningKo)
        assertEquals("pending_capture", result.sourceType)
    }

    private fun assertCandidateIncludes(vector: JSONObject, then: JSONObject, analysis: CaptureAnalysis) {
        if (!then.has("candidates_include")) return
        readStringList(then.getJSONArray("candidates_include")).forEach { candidate ->
            assertTrue("${vector.getString("name")} missing $candidate", analysis.candidates.contains(candidate))
        }
    }

    private fun assertCandidateExcludes(vector: JSONObject, then: JSONObject, analysis: CaptureAnalysis) {
        if (!then.has("candidates_exclude")) return
        readStringList(then.getJSONArray("candidates_exclude")).forEach { candidate ->
            assertTrue("${vector.getString("name")} unexpectedly included $candidate", !analysis.candidates.contains(candidate))
        }
    }

    private fun readStringList(array: JSONArray): List<String> {
        return (0 until array.length()).map { array.getString(it) }
    }

    private fun readCaptureVectors(): String {
        val candidates = listOf(
            File("../../../shared/test-vectors/capture_normalization.json"),
            File("../../shared/test-vectors/capture_normalization.json"),
            File("../shared/test-vectors/capture_normalization.json"),
            File("shared/test-vectors/capture_normalization.json"),
            File(System.getProperty("user.dir"), "../../../shared/test-vectors/capture_normalization.json"),
            File(System.getProperty("user.dir"), "../../shared/test-vectors/capture_normalization.json")
        )
        return candidates.firstOrNull { it.isFile }?.readText()
            ?: error("Could not find shared/test-vectors/capture_normalization.json from ${System.getProperty("user.dir")}")
    }
}
