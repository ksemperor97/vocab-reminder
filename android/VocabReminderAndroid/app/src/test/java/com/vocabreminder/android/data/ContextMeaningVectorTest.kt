package com.vocabreminder.android.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.vocabreminder.android.dictionary.DictionaryFallback
import com.vocabreminder.android.dictionary.DictionaryLookupRequest
import com.vocabreminder.android.dictionary.DictionaryLookupResult
import com.vocabreminder.android.dictionary.DictionaryLookupStatus
import com.vocabreminder.android.sync.CloudHttpResponse
import com.vocabreminder.android.sync.CloudSyncClient
import com.vocabreminder.android.sync.CloudSyncConfig
import com.vocabreminder.android.sync.CloudSyncException
import com.vocabreminder.android.sync.CloudSyncTransport
import com.vocabreminder.android.sync.DictionaryEnrichmentRuntime
import com.vocabreminder.android.sync.DictionaryEnrichmentWorker
import com.vocabreminder.android.sync.DictionaryLookupClient
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.Duration
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class ContextMeaningVectorTest {
    private lateinit var context: Context
    private lateinit var store: LocalVocabularyStore
    private val now = Instant.parse("2026-10-18T01:00:05Z")

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        store = LocalVocabularyStore(context)
        store.clearAll()
        DictionaryEnrichmentRuntime.now = { now }
    }

    @After
    fun tearDown() {
        DictionaryEnrichmentRuntime.resetForTests()
    }

    @Test
    fun sharedApplyCasesHoldOnAndroid() {
        val cases = JSONObject(readVectors()).getJSONArray("apply_cases")
        for (index in 0 until cases.length()) {
            store.clearAll()
            val case = cases.getJSONObject(index)
            val name = case.getString("name")
            val card = vectorCard(case.getJSONObject("card"))
            val before = store.definitionCandidates().map { it.id }.toSet()
            val result = lookupResult(case.getJSONObject("result"))

            // Same routing as DictionaryEnrichmentWorker.
            if (card.meaningKo.isBlank()) {
                store.recordContextAttempt(card, result, now)
                store.applyDictionaryEnrichment(card.id, result, now)
            } else {
                store.applyContextMeaning(card, result, now)
            }

            val expected = case.getJSONObject("expected")
            val after = store.findCard(card.id)!!
            val added = store.definitionCandidates().filter { it.id !in before }
            assertEquals(name, expected.getBoolean("applied"), after.version > card.version)
            assertEquals(name, expected.getString("meaning_ko"), after.meaningKo)
            assertEquals(name, expected.getString("definition_source_primary"), after.definitionSourcePrimary)
            assertEquals(name, strings(expected.getJSONArray("new_candidate_sources")).sorted(), added.map { it.source }.sorted())
            val selected = added.filter { it.selectedAsPrimary }.map { it.source }.singleOrNull()
            assertEquals(name, expected.optString("selected_candidate_source").takeIf { !expected.isNull("selected_candidate_source") }, selected)
            if (selected != null) {
                assertEquals(name, added.single { it.selectedAsPrimary }.id, after.primaryDefinitionCandidateId)
            }
            val retryAt = store.contextAttempts()[card.id]?.retryAt
            when (expected.getString("context_retry")) {
                "none" -> assertNull(name, retryAt)
                "soon" -> assertEquals(name, now.plus(Duration.ofMinutes(15)), retryAt)
                "later" -> assertEquals(name, now.plus(Duration.ofHours(24)), retryAt)
            }
            if (card.meaningKo.isNotBlank()) {
                assertEquals(name, card.dueAt, after.dueAt)
            }
        }
    }

    @Test
    fun workerSendsCapturedSentencesAndUpgradesDictionaryMeaningOnce() = runBlocking {
        store.saveCloudSyncCredentials(CloudSyncCredentials("https://sync.example.invalid", "test-token"))
        val blank = store.addCard(
            displayWord = "acute", meaningKo = "", exampleSentence = "The patient was admitted with acute appendicitis.",
            dueAt = now, now = now.minusSeconds(60), definitionSourcePrimary = "pending_capture"
        )
        val dictionary = store.addCard(
            displayWord = "bank", meaningKo = "은행", exampleSentence = "We had a picnic on the bank of the river.",
            dueAt = now.plusSeconds(3_600), now = now.minusSeconds(60), definitionSourcePrimary = "local_dictionary"
        )
        val noSentence = store.addCard(
            displayWord = "salient", meaningKo = "두드러진", exampleSentence = "",
            dueAt = now, now = now.minusSeconds(60), definitionSourcePrimary = "local_dictionary"
        )
        val requests = mutableListOf<List<DictionaryLookupRequest>>()
        DictionaryEnrichmentRuntime.clientFactory = {
            DictionaryLookupClient { items ->
                requests += items
                items.map { item ->
                    when (item.lemma) {
                        "acute" -> contextResult("acute", "급성의", item.sentence)
                        else -> contextResult("bank", "강둑, 둑", item.sentence, DictionaryFallback("은행", sourceType = "mac_dictionary"))
                    }
                }
            }
        }

        assertTrue(worker().doWork() is ListenableWorker.Result.Success)
        assertTrue(worker().doWork() is ListenableWorker.Result.Success)

        assertEquals(1, requests.size)
        assertEquals(
            listOf(
                DictionaryLookupRequest("acute", "The patient was admitted with acute appendicitis."),
                DictionaryLookupRequest("bank", "We had a picnic on the bank of the river.")
            ),
            requests.single()
        )
        assertEquals("급성의", store.findCard(blank.id)?.meaningKo)
        assertEquals("llm_context", store.findCard(blank.id)?.definitionSourcePrimary)
        val upgraded = store.findCard(dictionary.id)!!
        assertEquals("강둑, 둑", upgraded.meaningKo)
        assertEquals("llm_context", upgraded.definitionSourcePrimary)
        assertEquals(dictionary.dueAt, upgraded.dueAt)
        assertTrue(store.definitionCandidates().any { it.cardId == dictionary.id && it.parsedMeaningKo == "은행" })
        assertEquals("두드러진", store.findCard(noSentence.id)?.meaningKo)
        assertFalse(store.hasCardsNeedingContextMeaning(now))
    }

    @Test
    fun aCandidateChosenOnAnyDeviceKeepsTheCardOutOfTheContextQueue() {
        val card = store.addCard(
            displayWord = "bank", meaningKo = "은행", exampleSentence = "We had a picnic on the bank of the river.",
            dueAt = now, now = now.minusSeconds(60), definitionSourcePrimary = "local_dictionary"
        )
        assertEquals(listOf(card.id), store.cardsNeedingContextMeaning(10, now).map { it.id })
        val candidate = store.definitionCandidates().first { it.cardId == card.id }

        store.selectDefinitionCandidate(card.id, candidate.id, now)

        assertTrue(store.cardsNeedingContextMeaning(10, now).isEmpty())
    }

    @Test
    fun aChangedSentenceIsAskedAgain() {
        val card = store.addCard(
            displayWord = "bank", meaningKo = "은행", exampleSentence = "We had a picnic on the bank of the river.",
            dueAt = now, now = now.minusSeconds(60), definitionSourcePrimary = "local_dictionary"
        )
        store.applyContextMeaning(card, contextResult("bank", "은행", card.exampleSentence).copy(contextStatus = "unresolved", sourceType = "mac_dictionary"), now)
        assertTrue(store.cardsNeedingContextMeaning(10, now).isEmpty())

        store.commitLocal(LocalVocabularyStore.LocalChanges(cards = listOf(card.copy(exampleSentence = "She works at the bank.", version = card.version + 1))))

        assertEquals(listOf(card.id), store.cardsNeedingContextMeaning(10, now).map { it.id })
    }

    @Test
    fun clientSendsItemsWithSentencesAndWaitsLongerForThem() {
        val timeouts = mutableListOf<Int>()
        val bodies = mutableListOf<JSONObject>()
        val transport = object : CloudSyncTransport {
            override fun postJson(url: String, body: JSONObject, bearerToken: String, requestId: String) =
                postJson(url, body, bearerToken, requestId, -1)

            override fun postJson(url: String, body: JSONObject, bearerToken: String, requestId: String, readTimeoutMillis: Int): CloudHttpResponse {
                timeouts += readTimeoutMillis
                bodies += body
                val result = JSONObject()
                    .put("status", "resolved").put("lemma", "bank").put("meaningKo", "강둑").put("sourceType", "llm_context")
                    .put("contextStatus", "resolved").put("contextSentence", "We sat on the bank.")
                    .put("dictionary", JSONObject().put("meaningKo", "은행").put("sourceType", "mac_dictionary").put("confidence", 0.8))
                return CloudHttpResponse(JSONObject().put("results", JSONArray().put(result)), requestId)
            }

            override fun getJson(url: String, bearerToken: String, requestId: String) = error("unused")
        }
        val client = CloudSyncClient(CloudSyncConfig("https://sync.example.invalid", "token"), transport)

        val result = client.lookupDictionary(listOf(DictionaryLookupRequest("Bank", " We sat on the bank. "))).single()

        assertEquals(listOf(CloudSyncClient.CONTEXT_READ_TIMEOUT_MILLIS), timeouts)
        val item = bodies.single().getJSONArray("items").getJSONObject(0)
        assertEquals("bank", item.getString("lemma"))
        assertEquals("We sat on the bank.", item.getString("sentence"))
        assertEquals("resolved", result.contextStatus)
        assertEquals("llm_context", result.sourceType)
        assertEquals(DictionaryFallback("은행", sourceType = "mac_dictionary", confidence = 0.8), result.dictionary)
    }

    @Test
    fun clientFallsBackToTheLemmasBodyForAServerWithoutContextMeanings() {
        val bodies = mutableListOf<JSONObject>()
        val transport = object : CloudSyncTransport {
            override fun postJson(url: String, body: JSONObject, bearerToken: String, requestId: String): CloudHttpResponse {
                bodies += body
                if (body.has("items")) throw CloudSyncException("Sync HTTP 400.", requestId, httpStatus = 400)
                val result = JSONObject().put("status", "resolved").put("lemma", "bank").put("meaningKo", "은행").put("sourceType", "mac_dictionary")
                return CloudHttpResponse(JSONObject().put("results", JSONArray().put(result)), requestId)
            }

            override fun getJson(url: String, bearerToken: String, requestId: String) = error("unused")
        }
        val client = CloudSyncClient(CloudSyncConfig("https://sync.example.invalid", "token"), transport)

        val result = client.lookupDictionary(listOf(DictionaryLookupRequest("bank", "We sat on the bank."))).single()

        assertEquals(listOf(true, false), bodies.map { it.has("items") })
        assertEquals("은행", result.meaningKo)
        assertNull(result.contextStatus)
    }

    private fun vectorCard(json: JSONObject): VocabularyCard {
        val source = json.getString("definition_source_primary")
        val pinnedCandidate = json.getBoolean("manual_edit") && source != "user"
        val card = store.addCard(
            displayWord = json.getString("lemma"),
            meaningKo = json.getString("meaning_ko"),
            exampleSentence = json.getString("source_sentence_primary"),
            dueAt = now.plusSeconds(3_600),
            now = now.minusSeconds(600),
            definitionSourcePrimary = source
        )
        if (!pinnedCandidate) return card
        val candidate = store.definitionCandidates().first { it.cardId == card.id && it.source == source }
        return store.selectDefinitionCandidate(card.id, candidate.id, now.minusSeconds(300))!!.after
    }

    private fun lookupResult(json: JSONObject) = DictionaryLookupResult(
        status = DictionaryLookupStatus.RESOLVED,
        lemma = json.getString("lemma"),
        meaningKo = json.getString("meaningKo"),
        definitionEn = json.getString("definitionEn"),
        partOfSpeech = json.getString("partOfSpeech"),
        exampleSentence = json.getString("exampleSentence"),
        sourceType = json.getString("sourceType"),
        confidence = json.getDouble("confidence"),
        attemptedAt = Instant.parse(json.getString("attemptedAt")),
        contextStatus = json.getString("contextStatus"),
        contextSentence = json.getString("contextSentence"),
        dictionary = json.optJSONObject("dictionary")?.let {
            DictionaryFallback(
                meaningKo = it.getString("meaningKo"),
                definitionEn = it.getString("definitionEn"),
                partOfSpeech = it.getString("partOfSpeech"),
                exampleSentence = it.getString("exampleSentence"),
                sourceType = it.getString("sourceType"),
                confidence = it.getDouble("confidence")
            )
        }
    )

    private fun contextResult(lemma: String, meaning: String, sentence: String, dictionary: DictionaryFallback? = null) =
        DictionaryLookupResult(
            status = DictionaryLookupStatus.RESOLVED,
            lemma = lemma,
            meaningKo = meaning,
            definitionEn = "",
            partOfSpeech = "noun",
            sourceType = "llm_context",
            confidence = 0.9,
            attemptedAt = now,
            contextStatus = "resolved",
            contextSentence = sentence,
            dictionary = dictionary
        )

    private fun worker() = TestListenableWorkerBuilder<DictionaryEnrichmentWorker>(context).build()

    private fun strings(array: JSONArray) = (0 until array.length()).map { array.getString(it) }

    private fun readVectors(): String {
        val candidates = listOf(
            File("../../../shared/test-vectors/context_meanings.json"),
            File("../../shared/test-vectors/context_meanings.json"),
            File("../shared/test-vectors/context_meanings.json"),
            File("shared/test-vectors/context_meanings.json")
        )
        return candidates.firstOrNull { it.isFile }?.readText()
            ?: error("Could not find shared/test-vectors/context_meanings.json from ${System.getProperty("user.dir")}")
    }
}
