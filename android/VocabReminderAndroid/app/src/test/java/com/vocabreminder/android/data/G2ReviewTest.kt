package com.vocabreminder.android.data
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vocabreminder.android.scheduler.ReviewResponse
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[33], manifest=Config.NONE)
class G2ReviewTest {
    @Test fun retryAfterRestartCreatesExactlyOneReviewEvent() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val store=LocalVocabularyStore(context); store.clearAll()
        val now=Instant.parse("2026-09-09T01:00:00Z")
        val card=store.addCard(displayWord="example",meaningKo="예시",exampleSentence="",dueAt=now.minusSeconds(1),now=now.minusSeconds(60))
        val revision="${card.version}:${card.updatedAt}"
        val before=store.events().size
        assertEquals("applied",store.applyG2Response(card.id,revision,"action1",ReviewResponse.REMEMBERED,now))
        val reopened=LocalVocabularyStore(context)
        assertEquals("applied",reopened.applyG2Response(card.id,revision,"action1",ReviewResponse.REMEMBERED,now.plusSeconds(3)))
        assertEquals(before+1,reopened.events().size)
        assertTrue(reopened.findCard(card.id)!!.dueAt.isAfter(now))
        assertEquals("even_g2",reopened.events().last().metadata["source"])
        assertEquals("stale",reopened.applyG2Response(card.id,revision,"action2",ReviewResponse.FORGOTTEN,now.plusSeconds(4)))
        assertEquals(before+1,reopened.events().size)
    }
    @Test fun outdatedRevisionDoesNotChangeCardOrRecordAnAnswer() {
        val store=LocalVocabularyStore(ApplicationProvider.getApplicationContext<Context>());store.clearAll()
        val now=Instant.parse("2026-09-09T01:00:00Z")
        val card=store.addCard(displayWord="stale",meaningKo="오래된",exampleSentence="",dueAt=now.minusSeconds(1),now=now.minusSeconds(60))
        val before=store.events().size
        assertEquals("stale",store.applyG2Response(card.id,"old","action3",ReviewResponse.REMEMBERED,now))
        assertEquals(card,store.findCard(card.id));assertEquals(before,store.events().size)
    }
}
