package com.vocabreminder.android.data

import com.vocabreminder.android.scheduler.Wordbook
import java.time.Instant

/**
 * Field-group card merge (sync protocol 3), mirroring server/src/card_merge.js.
 *
 * Content (meaning, definition, example, provenance) and schedule (status, due
 * date, interval, lapses) each win by their own clock, so an edit on one device
 * and a review on another both survive. Deletion, wordbook promotion and the
 * recommendation start only ever move one way. Duplicate lemmas are folded by
 * the server, never here. The cases in shared/test-vectors/card_merge.json pin
 * every rule.
 */
internal object CardMerge {
    fun merge(existing: VocabularyCard, incoming: VocabularyCard): VocabularyCard {
        val content = if (compareGroup(
                incoming.contentVersion, incoming.contentUpdatedAt, contentKey(incoming),
                existing.contentVersion, existing.contentUpdatedAt, contentKey(existing)
            ) > 0
        ) incoming else existing
        val schedule = if (compareGroup(
                incoming.scheduleVersion, incoming.scheduleUpdatedAt, scheduleKey(incoming),
                existing.scheduleVersion, existing.scheduleUpdatedAt, scheduleKey(existing)
            ) > 0
        ) incoming else existing
        val newer = if (compareValuesBy(incoming, existing, { it.version }, { it.updatedAt }) > 0) incoming else existing
        val deletedAt = earliest(existing.deletedAt, incoming.deletedAt)
        return newer.copy(
            id = existing.id,
            lemma = existing.lemma,
            displayWord = content.displayWord,
            meaningKo = content.meaningKo,
            definitionEn = content.definitionEn,
            partOfSpeech = content.partOfSpeech,
            phonetic = content.phonetic,
            exampleSentence = content.exampleSentence,
            sourceAppPrimary = content.sourceAppPrimary,
            sourceUrlPrimary = content.sourceUrlPrimary,
            definitionSourcePrimary = content.definitionSourcePrimary,
            primaryDefinitionCandidateId = content.primaryDefinitionCandidateId,
            contentVersion = content.contentVersion,
            contentUpdatedAt = content.contentUpdatedAt,
            status = schedule.status,
            dueAt = schedule.dueAt,
            intervalDays = schedule.intervalDays,
            lapseCount = schedule.lapseCount,
            intervalBeforeLapse = schedule.intervalBeforeLapse,
            scheduleVersion = schedule.scheduleVersion,
            scheduleUpdatedAt = schedule.scheduleUpdatedAt,
            wordbook = if (existing.wordbook == Wordbook.USER || incoming.wordbook == Wordbook.USER) Wordbook.USER else Wordbook.RECOMMENDED,
            recommendationStartedAt = earliest(existing.recommendationStartedAt, incoming.recommendationStartedAt),
            deletedAt = deletedAt,
            mergedInto = if (deletedAt != null) existing.mergedInto ?: incoming.mergedInto else null,
            createdAt = minOf(existing.createdAt, incoming.createdAt),
            notificationId = existing.notificationId
        )
    }

    /**
     * Advances the group clocks for a local write: whichever group's fields
     * changed relative to the stored card gets a new version and timestamp.
     */
    fun stamp(previous: VocabularyCard?, next: VocabularyCard): VocabularyCard {
        if (previous == null) return next
        var stamped = next
        if (contentKey(previous) != contentKey(next) && next.contentVersion <= previous.contentVersion) {
            stamped = stamped.copy(contentVersion = previous.contentVersion + 1, contentUpdatedAt = next.updatedAt)
        }
        if (scheduleKey(previous) != scheduleKey(next) && next.scheduleVersion <= previous.scheduleVersion) {
            stamped = stamped.copy(scheduleVersion = previous.scheduleVersion + 1, scheduleUpdatedAt = next.updatedAt)
        }
        if (stamped != previous && stamped.version <= previous.version) {
            stamped = stamped.copy(version = previous.version + 1)
        }
        return stamped
    }

    // Keys match groupKey() in card_merge.js field for field (wire names, nulls
    // as "", due_at as epoch millis) so tie-breaks agree on every replica.
    internal fun contentKey(card: VocabularyCard): String = listOf(
        card.displayWord,
        card.meaningKo,
        card.definitionEn,
        card.partOfSpeech,
        card.phonetic,
        card.exampleSentence,
        card.sourceAppPrimary,
        card.sourceUrlPrimary,
        card.definitionSourcePrimary,
        card.primaryDefinitionCandidateId.orEmpty()
    ).joinToString(SEPARATOR)

    internal fun scheduleKey(card: VocabularyCard): String = listOf(
        card.status.name.lowercase(),
        card.dueAt.toEpochMilli().toString(),
        card.intervalDays.toString(),
        card.lapseCount.toString(),
        card.intervalBeforeLapse?.toString().orEmpty(),
        WIRE_EASE,
        "",
        ""
    ).joinToString(SEPARATOR)

    private fun compareGroup(
        versionA: Int, updatedA: Instant, keyA: String,
        versionB: Int, updatedB: Instant, keyB: String
    ): Int {
        if (versionA != versionB) return versionA.compareTo(versionB)
        val time = updatedA.compareTo(updatedB)
        if (time != 0) return time
        return keyA.compareTo(keyB)
    }

    private fun earliest(a: Instant?, b: Instant?): Instant? = when {
        a == null -> b
        b == null -> a
        else -> minOf(a, b)
    }

    private const val SEPARATOR = "\u001f"
    private const val WIRE_EASE = "2.5"
}
