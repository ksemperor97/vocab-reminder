package com.vocabreminder.android.notifications

import java.time.Clock
import java.time.Instant

/** Single time source for notification planning and delivery. */
internal object NotificationClock {
    @Volatile
    private var clock: Clock = Clock.systemUTC()

    fun now(): Instant = clock.instant()

    internal fun replaceForTest(clock: Clock) {
        this.clock = clock
    }

    internal fun resetForTest() {
        clock = Clock.systemUTC()
    }
}
