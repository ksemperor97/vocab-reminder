package com.vocabreminder.android.scheduler

import java.time.Duration
import java.time.Instant
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.random.Random

object DueTimeJitter {
    fun apply(
        dueAt: Instant,
        now: Instant,
        settings: SchedulerSettings,
        offsetProvider: ((LongRange) -> Long)? = null
    ): Instant {
        val horizonSeconds = Duration.between(now, dueAt).seconds
        if (horizonSeconds <= 60) return Scheduler.avoidQuietHours(dueAt, settings)

        val windowSeconds = jitterWindowSeconds(horizonSeconds)
        if (windowSeconds < 1) return Scheduler.avoidQuietHours(dueAt, settings)

        val range = -windowSeconds..windowSeconds
        val offset = offsetProvider?.invoke(range) ?: Random.nextLong(range.first, range.last + 1)
        val minimumDueAt = now.plusSeconds(60)
        val candidate = dueAt.plusSeconds(offset).let { if (it.isBefore(minimumDueAt)) minimumDueAt else it }
        return Scheduler.avoidQuietHours(candidate, settings)
    }

    fun apply(
        result: ReviewResult,
        now: Instant,
        settings: SchedulerSettings,
        offsetProvider: ((LongRange) -> Long)? = null
    ): ReviewResult {
        val dueAt = apply(result.card.dueAt, now, settings, offsetProvider)
        if (dueAt == result.card.dueAt) return result

        return result.copy(
            card = result.card.copy(dueAt = dueAt),
            event = result.event.copy(
                nextDueAt = dueAt,
                metadata = result.event.metadata + ("due_jitter_seconds" to Duration.between(result.card.dueAt, dueAt).seconds.toString())
            )
        )
    }

    fun jitterWindowSeconds(horizonSeconds: Long): Long {
        val horizon = horizonSeconds.toDouble()
        return when {
            horizonSeconds <= 30 * 60 -> min(max(horizon * 0.5, 2 * 60.0), 10 * 60.0)
            horizonSeconds <= 6 * 60 * 60 -> min(max(horizon * 0.2, 10 * 60.0), 60 * 60.0)
            else -> min(max(horizon * 0.1, 60 * 60.0), 12 * 60 * 60.0)
        }.roundToLong().coerceAtLeast(1)
    }
}
