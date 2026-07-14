package com.vocabreminder.android.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.Instant
import com.vocabreminder.android.scheduler.CardStatus
import com.vocabreminder.android.scheduler.ReviewResponse

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class LocalVocabularyStoreSyncTest {
    private lateinit var store: LocalVocabularyStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        store = LocalVocabularyStore(context)
        store.clearAll()
    }

    @Test
    fun importSyncBatchKeepsNewerLocalSettings() {
        val newer = "2026-07-03T02:00:00Z"
        store.importSyncBatchJson(syncPayload(settings(goal = 11, updatedAt = newer)).toString())

        store.importSyncBatchJson(
            syncPayload(settings(goal = 3, updatedAt = "2026-07-03T01:00:00Z")).toString()
        )

        assertEquals(11, store.settings().dailyWordGoal)
        assertEquals(Instant.parse(newer), store.settings().updatedAt)
    }

    @Test
    fun importSyncBatchAppliesNewerIncomingSettingsWithoutRestamping() {
        val newer = "2026-07-03T03:00:00Z"
        store.importSyncBatchJson(syncPayload(settings(goal = 11, updatedAt = "2026-07-03T02:00:00Z")).toString())
        store.importSyncBatchJson(syncPayload(settings(goal = 12, updatedAt = newer)).toString())

        assertEquals(12, store.settings().dailyWordGoal)
        assertEquals(Instant.parse(newer), store.settings().updatedAt)
    }

    @Test
    fun importSyncBatchPreservesDeviceLocalWatchReplyToggle() {
        store.saveSettings(store.settings().copy(enableWatchReplyReview = false))
        val incomingUpdatedAt = Instant.now().plusSeconds(60).toString()

        // Incoming settings are newer and (like Mac payloads) carry no watch reply field.
        store.importSyncBatchJson(
            syncPayload(settings(goal = 15, updatedAt = incomingUpdatedAt)).toString()
        )

        assertEquals(15, store.settings().dailyWordGoal)
        assertEquals(false, store.settings().enableWatchReplyReview)
    }

    @Test
    fun exportedSettingsPayloadOmitsDeviceLocalWatchReplyToggle() {
        val exported = JSONObject(store.exportSyncBatchJson())

        assertTrue(!exported.getJSONObject("settings").has("enable_watch_reply_review"))
    }

    @Test
    fun definitionCandidateChangesRefreshTheCardHistoryUi() {
        assertTrue(store.isUiRefreshKey("definition_candidates"))
    }

    @Test
    fun corruptedSettingsFallBackToDefaults() {
        store.importSyncBatchJson(
            syncPayload(
                settings(goal = 12, updatedAt = "2026-07-03T05:00:00Z")
                    .put("quiet_hours_start", "25:00")
                    .put("quiet_hours_end", "99:99")
                    .put("timezone", "Not/AZone")
            ).toString()
        )

        val settings = store.settings()
        assertEquals("23:00", settings.quietHoursStart.toString())
        assertEquals("09:00", settings.quietHoursEnd.toString())
        assertEquals("Asia/Seoul", settings.timezone.id)
    }

    @Test
    fun deviceIdIsPersistentAndUsedInExportedPayload() {
        val first = store.deviceId()
        val second = store.deviceId()

        assertEquals(first, second)
        assertTrue(first.matches(Regex("^android-[0-9a-f]{8}$")))

        store.addCard(
            displayWord = "stable",
            meaningKo = "안정적인",
            exampleSentence = "A stable identifier is useful.",
            dueAt = Instant.parse("2026-07-03T00:00:00Z"),
            now = Instant.parse("2026-07-03T00:00:00Z")
        )
        val exported = JSONObject(store.exportSyncBatchJson())
        assertEquals(first, exported.getJSONArray("cards").getJSONObject(0).getString("created_device_id"))
        assertEquals(first, exported.getJSONArray("contexts").getJSONObject(0).getString("created_device_id"))
        assertEquals(first, store.deviceId())
    }

    @Test
    fun editCardDetailsAppendsTheNewMeaningCandidateOnEverySave() {
        val now = Instant.parse("2026-07-03T00:00:00Z")
        val card = store.addCard(
            displayWord = "editable",
            meaningKo = "이전 뜻",
            definitionEn = "old definition",
            exampleSentence = "Old example.",
            dueAt = now.plusSeconds(600),
            now = now
        )

        val result = store.updateCardDetails(
            cardId = card.id,
            meaningKo = "새 뜻",
            exampleSentence = "New example.",
            now = now.plusSeconds(60)
        )

        val updated = store.findCard(card.id)!!
        val event = store.events().single()
        val candidates = store.definitionCandidates()
        val previousCandidate = candidates.first()
        val newCandidate = candidates.last()
        assertEquals(card.id, result?.after?.id)
        assertEquals("새 뜻", updated.meaningKo)
        assertEquals("New example.", updated.exampleSentence)
        assertEquals(card.version + 1, updated.version)
        assertEquals(ReviewResponse.MANUAL_EDIT, event.response)
        assertEquals("manual_edit", event.metadata["action"])
        assertEquals("이전 뜻", previousCandidate.parsedMeaningKo)
        assertEquals("old definition", previousCandidate.parsedDefinitionEn)
        assertEquals("새 뜻", newCandidate.parsedMeaningKo)
        assertEquals("새 뜻", newCandidate.rawText)
        assertEquals("user", newCandidate.source)
        assertEquals(true, newCandidate.selectedAsPrimary)
        assertEquals(store.deviceId(), newCandidate.originDeviceId)
        assertEquals(card.version, newCandidate.baseCardVersion)

        store.updateCardDetails(
            cardId = card.id,
            meaningKo = "새 뜻",
            exampleSentence = "Another example.",
            now = now.plusSeconds(120)
        )
        val afterSecondSave = store.definitionCandidates()
        assertEquals(3, afterSecondSave.size)
        assertEquals(3, afterSecondSave.map { it.id }.toSet().size)
        assertEquals("새 뜻", afterSecondSave.last().parsedMeaningKo)
        assertEquals("Another example.", afterSecondSave.last().exampleSentence)
    }

    @Test
    fun importedConcurrentMeaningCandidatesRemainAfterCardWinnerIsSelected() {
        val id = "concurrent-meaning"
        val updatedAt = "2026-07-03T02:00:00Z"
        val payload = syncPayload(settings(goal = 10, updatedAt = "2026-07-03T00:00:00Z"))
            .put("cards", JSONArray().put(card(id, "zeta meaning", version = 2, updatedAt = updatedAt)))
            .put(
                "definition_candidates",
                JSONArray()
                    .put(candidate("candidate-alpha", id, "alpha meaning", updatedAt))
                    .put(candidate("candidate-zeta", id, "zeta meaning", updatedAt))
            )

        store.importSyncBatchJson(payload.toString())
        store.importSyncBatchJson(payload.toString())

        assertEquals("zeta meaning", store.findCard(id)?.meaningKo)
        assertEquals(
            setOf("alpha meaning", "zeta meaning"),
            store.definitionCandidates().map { it.parsedMeaningKo }.toSet()
        )
        assertEquals(2, store.definitionCandidates().size)
    }

    @Test
    fun conflictCandidatesSurvivePrimaryRestoreAndExportReimport() {
        val cardId = "meaning-conflict-roundtrip"
        val conflictedAt = "2026-07-13T02:00:00Z"
        val payload = syncPayload(settings(goal = 10, updatedAt = "2026-07-13T00:00:00Z"))
            .put("cards", JSONArray().put(card(cardId, "두 번째 뜻", version = 2, updatedAt = conflictedAt)))
            .put(
                "definition_candidates",
                JSONArray()
                    .put(candidate("meaning-one", cardId, "첫 번째 뜻", conflictedAt, "android-a", 1))
                    .put(candidate("meaning-two", cardId, "두 번째 뜻", conflictedAt, "mac-b", 1))
            )
        store.importSyncBatchJson(payload.toString())

        val restored = store.selectDefinitionCandidate(
            cardId = cardId,
            candidateId = "meaning-one",
            now = Instant.parse("2026-07-13T02:01:00Z")
        )!!

        assertEquals("첫 번째 뜻", restored.after.meaningKo)
        assertEquals(
            setOf("첫 번째 뜻", "두 번째 뜻"),
            store.definitionCandidates().map { it.parsedMeaningKo }.toSet()
        )
        val history = CardHistoryBuilder.build(
            card = restored.after,
            contexts = store.contexts(),
            candidates = store.definitionCandidates(),
            events = store.events()
        )
        val definitions = history.filterIsInstance<CardHistoryEntry.DefinitionEntry>()
        assertEquals(setOf("meaning-one", "meaning-two"), definitions.filter { it.isConflictCandidate }.map { it.candidate.id }.toSet())
        val restoredPrimary = definitions.single { it.isCurrentPrimary }.candidate
        assertTrue(restoredPrimary.id !in setOf("meaning-one", "meaning-two"))
        assertEquals(store.deviceId(), restoredPrimary.originDeviceId)
        assertEquals(restored.before.version, restoredPrimary.baseCardVersion)

        val exported = store.exportSyncBatchJson()
        store.clearAll()
        store.importSyncBatchJson(exported)

        assertEquals("첫 번째 뜻", store.findCard(cardId)?.meaningKo)
        assertTrue(
            store.definitionCandidates().map { it.id }.toSet()
                .containsAll(setOf("meaning-one", "meaning-two"))
        )
        assertEquals(
            setOf("첫 번째 뜻", "두 번째 뜻"),
            store.definitionCandidates().map { it.parsedMeaningKo }.toSet()
        )
        assertEquals(
            "meaning-one",
            store.events().single { it.metadata["action"] == "select_definition_candidate" }
                .metadata["candidate_id"]
        )
    }

    @Test
    fun sameBaseRestoresFromDifferentDevicesAreMarkedAsConflicts() {
        val cardId = "restore-lineage-conflict"
        store.importSyncBatchJson(
            syncPayload(settings(goal = 10, updatedAt = "2026-07-13T00:00:00Z"))
                .put("cards", JSONArray().put(card(cardId, "기준 뜻", version = 2, updatedAt = "2026-07-13T02:00:00Z")))
                .put(
                    "definition_candidates",
                    JSONArray().put(candidate("historical-one", cardId, "첫 번째 뜻", "2026-07-13T01:00:00Z"))
                )
                .toString()
        )

        val localRestore = store.selectDefinitionCandidate(
            cardId = cardId,
            candidateId = "historical-one",
            now = Instant.parse("2026-07-13T02:01:00Z")
        )!!
        val localCandidate = store.definitionCandidates().single {
            it.originDeviceId == store.deviceId() && it.baseCardVersion == 2
        }

        store.importSyncBatchJson(
            syncPayload(settings(goal = 10, updatedAt = "2026-07-13T00:00:00Z"))
                .put("cards", JSONArray().put(card(cardId, "다른 기기 뜻", version = 3, updatedAt = "2026-07-13T02:02:00Z")))
                .put(
                    "definition_candidates",
                    JSONArray().put(
                        candidate(
                            "remote-restore",
                            cardId,
                            "다른 기기 뜻",
                            "2026-07-13T02:02:00Z",
                            "mac-b",
                            2
                        )
                    )
                )
                .toString()
        )

        assertEquals(3, localRestore.after.version)
        val mergedCard = store.findCard(cardId)!!
        val conflicts = CardHistoryBuilder.build(
            card = mergedCard,
            contexts = store.contexts(),
            candidates = store.definitionCandidates(),
            events = store.events()
        ).filterIsInstance<CardHistoryEntry.DefinitionEntry>()
            .filter { it.isConflictCandidate }
            .map { it.candidate.id }
            .toSet()
        assertEquals(setOf(localCandidate.id, "remote-restore"), conflicts)
    }

    @Test
    fun sharedMeaningConflictFixtureMatchesMergeExportAndRestoreContract() {
        val fixture = JSONObject(meaningConflictFixtureFile().readText())
        val cases = fixture.getJSONArray("cases")

        for (index in 0 until cases.length()) {
            val fixtureCase = cases.getJSONObject(index)
            val androidOffline = fixtureCase.getJSONObject("android_offline")
            val macOffline = fixtureCase.getJSONObject("mac_offline")
            val expected = fixtureCase.getJSONObject("expected")

            store.clearAll()
            store.importSyncBatchJson(androidOffline.toString())
            store.importSyncBatchJson(macOffline.toString())
            assertMeaningConflictState(expected)
            val forwardExport = store.exportSyncBatchJson()

            store.clearAll()
            store.importSyncBatchJson(macOffline.toString())
            store.importSyncBatchJson(androidOffline.toString())
            assertMeaningConflictState(expected)

            store.clearAll()
            store.importSyncBatchJson(forwardExport)
            assertMeaningConflictState(expected)

            val restore = expected.getJSONObject("restore")
            val restored = store.selectDefinitionCandidate(
                cardId = expected.getString("card_id"),
                candidateId = restore.getString("candidate_id"),
                now = Instant.parse(restore.getString("updated_at"))
            )
            assertEquals(restore.getString("meaning_ko"), restored?.after?.meaningKo)

            val restoredExport = store.exportSyncBatchJson()
            store.clearAll()
            store.importSyncBatchJson(restoredExport)

            val cardId = expected.getString("card_id")
            val preservedIds = expected.getJSONArray("preserved_candidate_ids").toStringSet()
            assertEquals(restore.getString("meaning_ko"), store.findCard(cardId)?.meaningKo)
            assertTrue(
                store.definitionCandidates()
                    .filter { it.cardId == cardId }
                    .map { it.id }
                    .toSet()
                    .containsAll(preservedIds)
            )
        }
    }

    @Test
    fun eventArchiveKeepsActiveEventsUnderThresholdAndRecentResponsesStable() {
        val cardId = "archive-card"
        store.recordCloudSyncSuccess()
        val payload = syncPayload(settings(goal = 10, updatedAt = "2026-07-03T06:00:00Z"))
        val events = JSONArray()
        repeat(6_000) { index ->
            events.put(reviewEvent(cardId, index))
        }
        payload.put("review_events", events)

        store.importSyncBatchJson(payload.toString())
        // A pulled snapshot can contain events that this device archived previously.
        // Re-archiving them must not append duplicate IDs forever.
        store.importSyncBatchJson(payload.toString())

        assertEquals(5_000, store.events().size)
        assertEquals(1_000, store.archivedEvents().size)
        assertEquals(
            listOf(ReviewResponse.REMEMBERED, ReviewResponse.FORGOTTEN),
            store.recentResponses(cardId, 2)
        )
        assertTrue(JSONObject(store.exportSyncBatchJson()).getJSONArray("review_events").length() <= 5_000)
    }

    @Test
    fun recentResponsesIgnoreAdministrativeCardHistoryEvents() {
        val cardId = "learning-response-filter"
        val events = JSONArray()
            .put(reviewEvent(cardId, 1).put("response", "remembered"))
            .put(reviewEvent(cardId, 2).put("response", "manual_edit"))
            .put(reviewEvent(cardId, 3).put("response", "suspend"))
            .put(reviewEvent(cardId, 4).put("response", "restore"))
            .put(reviewEvent(cardId, 5).put("response", "remembered"))
        store.importSyncBatchJson(
            syncPayload(settings(goal = 10, updatedAt = "2026-07-03T06:00:00Z"))
                .put("review_events", events)
                .toString()
        )

        assertEquals(
            listOf(ReviewResponse.REMEMBERED, ReviewResponse.REMEMBERED),
            store.recentResponses(cardId, 2)
        )
    }

    @Test
    fun cardMergePrefersHigherVersionEvenWhenItsTimestampIsOlder() {
        val id = "version-wins"
        store.importSyncBatchJson(
            syncPayload(settings(goal = 10, updatedAt = "2026-07-03T00:00:00Z"))
                .put(
                    "cards",
                    JSONArray().put(card(id, "high-version", version = 5, updatedAt = "2026-07-03T01:00:00Z"))
                )
                .toString()
        )

        store.importSyncBatchJson(
            syncPayload(settings(goal = 10, updatedAt = "2026-07-03T00:00:00Z"))
                .put(
                    "cards",
                    JSONArray().put(card(id, "newer-clock", version = 4, updatedAt = "2026-07-03T05:00:00Z"))
                )
                .toString()
        )

        assertEquals("high-version", store.findCard(id)?.meaningKo)
        assertEquals(5, store.findCard(id)?.version)
    }

    @Test
    fun equalVersionCardMergeUsesLaterTimestamp() {
        val id = "timestamp-tiebreak"
        store.importSyncBatchJson(
            syncPayload(settings(goal = 10, updatedAt = "2026-07-03T00:00:00Z"))
                .put(
                    "cards",
                    JSONArray().put(card(id, "older", version = 3, updatedAt = "2026-07-03T01:00:00Z"))
                )
                .toString()
        )

        store.importSyncBatchJson(
            syncPayload(settings(goal = 10, updatedAt = "2026-07-03T00:00:00Z"))
                .put(
                    "cards",
                    JSONArray().put(card(id, "later", version = 3, updatedAt = "2026-07-03T02:00:00Z"))
                )
                .toString()
        )

        assertEquals("later", store.findCard(id)?.meaningKo)
    }

    @Test
    fun exactVersionAndTimestampTiePrefersTombstone() {
        val id = "tombstone-tiebreak"
        val updatedAt = "2026-07-03T02:00:00Z"
        store.importSyncBatchJson(
            syncPayload(settings(goal = 10, updatedAt = "2026-07-03T00:00:00Z"))
                .put("cards", JSONArray().put(card(id, "active", version = 3, updatedAt = updatedAt)))
                .toString()
        )

        store.importSyncBatchJson(
            syncPayload(settings(goal = 10, updatedAt = "2026-07-03T00:00:00Z"))
                .put(
                    "cards",
                    JSONArray().put(
                        card(id, "deleted", version = 3, updatedAt = updatedAt)
                            .put("deleted_at", updatedAt)
                    )
                )
                .toString()
        )

        assertEquals(null, store.findCard(id))
        assertEquals(Instant.parse(updatedAt), store.allCards(includeDeleted = true).single { it.id == id }.deletedAt)
    }

    @Test
    fun tiedTombstonesPreferLaterDeletedAtInBothImportDirections() {
        val id = "later-tombstone"
        val updatedAt = "2026-07-03T02:00:00Z"
        val earlierDeletedAt = "2026-07-03T02:01:00Z"
        val laterDeletedAt = "2026-07-03T02:02:00Z"
        val earlier = card(id, "deleted", version = 3, updatedAt = updatedAt)
            .put("deleted_at", earlierDeletedAt)
        val later = card(id, "deleted", version = 3, updatedAt = updatedAt)
            .put("deleted_at", laterDeletedAt)

        fun import(cardJson: JSONObject) {
            store.importSyncBatchJson(
                syncPayload(settings(goal = 10, updatedAt = "2026-07-03T00:00:00Z"))
                    .put("cards", JSONArray().put(cardJson))
                    .toString()
            )
        }

        import(earlier)
        import(later)
        val forward = store.allCards(includeDeleted = true).single { it.id == id }.deletedAt

        store.clearAll()
        import(later)
        import(earlier)
        val reverse = store.allCards(includeDeleted = true).single { it.id == id }.deletedAt

        assertEquals(Instant.parse(laterDeletedAt), forward)
        assertEquals(forward, reverse)
    }

    @Test
    fun exactActiveTupleTieUsesSameStableWinnerInBothImportDirections() {
        val id = "stable-active-tie"
        val updatedAt = "2026-07-03T02:00:00Z"
        val alpha = card(id, "alpha", version = 3, updatedAt = updatedAt)
        val zeta = card(id, "zeta", version = 3, updatedAt = updatedAt)

        store.importSyncBatchJson(
            syncPayload(settings(goal = 10, updatedAt = "2026-07-03T00:00:00Z"))
                .put("cards", JSONArray().put(alpha))
                .toString()
        )
        store.importSyncBatchJson(
            syncPayload(settings(goal = 10, updatedAt = "2026-07-03T00:00:00Z"))
                .put("cards", JSONArray().put(zeta))
                .toString()
        )
        val forwardWinner = store.findCard(id)?.meaningKo

        store.clearAll()
        store.importSyncBatchJson(
            syncPayload(settings(goal = 10, updatedAt = "2026-07-03T00:00:00Z"))
                .put("cards", JSONArray().put(zeta))
                .toString()
        )
        store.importSyncBatchJson(
            syncPayload(settings(goal = 10, updatedAt = "2026-07-03T00:00:00Z"))
                .put("cards", JSONArray().put(alpha))
                .toString()
        )
        val reverseWinner = store.findCard(id)?.meaningKo

        assertEquals("zeta", forwardWinner)
        assertEquals(forwardWinner, reverseWinner)
    }

    private fun syncPayload(settings: JSONObject): JSONObject {
        return JSONObject()
            .put("cards", JSONArray())
            .put("contexts", JSONArray())
            .put("definition_candidates", JSONArray())
            .put("review_events", JSONArray())
            .put("recommendation_feedback", JSONArray())
            .put("settings", settings)
    }

    private fun assertMeaningConflictState(expected: JSONObject) {
        val cardId = expected.getString("card_id")
        val primary = expected.getJSONObject("primary")
        val candidates = store.definitionCandidates().filter { it.cardId == cardId }
        assertEquals(primary.getString("meaning_ko"), store.findCard(cardId)?.meaningKo)
        assertEquals(
            expected.getJSONArray("preserved_candidate_ids").toStringSet(),
            candidates.map { it.id }.toSet()
        )
        assertEquals(
            primary.getString("meaning_ko"),
            candidates.single { it.id == primary.getString("candidate_id") }.parsedMeaningKo
        )
        val expectedConflictIds = expected.getJSONArray("conflict_candidate_ids").toStringSet()
        val card = store.findCard(cardId)!!
        val conflictIds = CardHistoryBuilder.build(card, store.contexts(), candidates, store.events())
            .filterIsInstance<CardHistoryEntry.DefinitionEntry>()
            .filter { it.isConflictCandidate }
            .map { it.candidate.id }
            .toSet()
        assertEquals(expectedConflictIds, conflictIds)
    }

    private fun meaningConflictFixtureFile(): File {
        return listOf(
            File("../../../shared/test-vectors/meaning_conflicts.json"),
            File("../../shared/test-vectors/meaning_conflicts.json"),
            File("../shared/test-vectors/meaning_conflicts.json"),
            File("shared/test-vectors/meaning_conflicts.json"),
            File(System.getProperty("user.dir"), "../../../shared/test-vectors/meaning_conflicts.json"),
            File(System.getProperty("user.dir"), "../../shared/test-vectors/meaning_conflicts.json")
        ).firstOrNull { it.isFile }
            ?: error("Could not find shared/test-vectors/meaning_conflicts.json from ${System.getProperty("user.dir")}")
    }

    private fun JSONArray.toStringSet(): Set<String> {
        return (0 until length()).map { getString(it) }.toSet()
    }

    private fun settings(goal: Int, updatedAt: String): JSONObject {
        return JSONObject()
            .put("daily_word_goal", goal)
            .put("max_queue_size", 20)
            .put("snooze_duration_minutes", 60)
            .put("quiet_hours_start", "23:00")
            .put("quiet_hours_end", "09:00")
            .put("timezone", "Asia/Seoul")
            .put("enable_word_recommendations", true)
            .put("settings_updated_at", updatedAt)
    }

    private fun reviewEvent(cardId: String, index: Int): JSONObject {
        val response = if (index % 2 == 0) "remembered" else "forgotten"
        val createdAt = Instant.parse("2026-01-01T00:00:00Z").plusSeconds(index.toLong())
        return JSONObject()
            .put("id", "event-$index")
            .put("card_id", cardId)
            .put("response", response)
            .put("previous_status", CardStatus.NEW.name.lowercase())
            .put("next_status", CardStatus.LEARNING.name.lowercase())
            .put("previous_due_at", createdAt.toString())
            .put("next_due_at", createdAt.plusSeconds(600).toString())
            .put("previous_interval_days", 0)
            .put("next_interval_days", 1)
            .put("client_created_at", createdAt.toString())
            .put("metadata", JSONObject())
    }

    private fun card(id: String, meaning: String, version: Int, updatedAt: String): JSONObject {
        return JSONObject()
            .put("id", id)
            .put("lemma", id)
            .put("display_word", id)
            .put("meaning_ko", meaning)
            .put("definition_en", "definition")
            .put("part_of_speech", "noun")
            .put("phonetic", "")
            .put("example_sentence", "Example.")
            .put("source_app_primary", "test")
            .put("source_url_primary", "")
            .put("definition_source_primary", "user")
            .put("status", CardStatus.NEW.name.lowercase())
            .put("wordbook", "user")
            .put("due_at", "2026-07-12T01:00:00Z")
            .put("interval_days", 0)
            .put("lapse_count", 0)
            .put("interval_before_lapse", JSONObject.NULL)
            .put("recommendation_started_at", JSONObject.NULL)
            .put("deleted_at", JSONObject.NULL)
            .put("notification_id", id.hashCode() and 0x7fffffff)
            .put("created_at", "2026-07-03T00:00:00Z")
            .put("updated_at", updatedAt)
            .put("version", version)
    }

    private fun candidate(
        id: String,
        cardId: String,
        meaning: String,
        createdAt: String,
        originDeviceId: String? = null,
        baseCardVersion: Int? = null
    ): JSONObject {
        return JSONObject()
            .put("id", id)
            .put("card_id", cardId)
            .put("source", "user")
            .put("raw_text", meaning)
            .put("parsed_meaning_ko", meaning)
            .put("parsed_definition_en", "")
            .put("part_of_speech", "")
            .put("phonetic", "")
            .put("example_sentence", "")
            .put("confidence", 1.0)
            .put("selected_as_primary", true)
            .put("origin_device_id", originDeviceId ?: JSONObject.NULL)
            .put("base_card_version", baseCardVersion ?: JSONObject.NULL)
            .put("created_at", createdAt)
    }
}
