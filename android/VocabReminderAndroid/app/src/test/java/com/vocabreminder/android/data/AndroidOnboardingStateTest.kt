package com.vocabreminder.android.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class AndroidOnboardingStateTest {
    private lateinit var store: LocalVocabularyStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        store = LocalVocabularyStore(context)
        store.clearAll()
    }

    @Test
    fun captureGuideCompletionAndFirstLaunchTimePersist() {
        val firstLaunch = Instant.parse("2026-07-13T00:00:00Z")
        val completedAt = firstLaunch.plusSeconds(30)

        val initial = store.onboardingState(firstLaunch)
        assertEquals(firstLaunch, initial.firstLaunchAt)
        assertNull(initial.captureGuideCompletedAt)

        store.completeCaptureGuide(completedAt)
        val restored = store.onboardingState(firstLaunch.plusSeconds(3_600))

        assertEquals(firstLaunch, restored.firstLaunchAt)
        assertEquals(completedAt, restored.captureGuideCompletedAt)
    }
}
